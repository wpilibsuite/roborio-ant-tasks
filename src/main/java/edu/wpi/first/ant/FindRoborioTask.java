package edu.wpi.first.ant;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.URI;
import java.util.HashSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;

public class FindRoborioTask extends Task {
    private String team;
    private String username = null;
    private String password = "";
    private String targetProperty;
    private String imageYearProperty = null;
    private String imageProperty = null;

    private Pattern imagePattern = Pattern.compile("\"FRC_roboRIO_(?<year>[0-9]+)_v(?<image>[0-9]+)\"");
    private Pattern dsPattern = Pattern.compile("\"robotIP\"[^:]*:[^0-9]*([0-9]+)");

    private final Lock lock = new ReentrantLock();
    private final Condition cvDone = lock.newCondition();
    private boolean done = false;
    private HashSet<String> attempts = new HashSet<String>();
    private InetAddress target = null;
    private String imageYear = null;
    private String image = null;

    private static final int CONNECTION_TIMEOUT_MS = 2000;
    private static final int TOTAL_TIMEOUT_SEC = 20;

    public void setTeam(String team) {
        this.team = team;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setTargetProperty(String targetProperty) {
        this.targetProperty = targetProperty;
    }

    public void setImageYearProperty(String imageYearProperty) {
        this.imageYearProperty = imageYearProperty;
    }

    public void setImageProperty(String imageProperty) {
        this.imageProperty = imageProperty;
    }

    @Override
    public void execute() throws BuildException {
        if (this.team == null)
            throw new BuildException("team must not be null");

        // team must be an integer
        int team = Integer.parseInt(this.team);

        // start connection attempts to various address possibilities
        startConnect(new byte[] {10, (byte)(team / 100), (byte)(team % 100), 2});
        startConnect(new byte[] {(byte)172, 22, 11, 2});
        startConnect("roboRIO-" + team + "-FRC.local");
        startConnect("roboRIO-" + team + "-FRC.lan");
        startConnect("roboRIO-" + team + "-FRC.frc-field.local");
        startDsConnect();

        // wait for a connection attempt to be successful, or timeout
        lock.lock();
        try {
            while (!done && !attempts.isEmpty()) {
                try {
                    if (!cvDone.await(TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        log("Timed out.", Project.MSG_WARN);
                        return;
                    }
                } catch (InterruptedException e) {
                    log("Interrupted.", e, Project.MSG_WARN);
                    return;
                }
            }

            // set properties from connection results
            if (targetProperty != null && target != null)
                getProject().setNewProperty(targetProperty, target.getHostAddress());
            if (imageYearProperty != null && imageYear != null)
                getProject().setNewProperty(imageYearProperty, imageYear);
            if (imageProperty != null && image != null)
                getProject().setNewProperty(imageProperty, image);
        } finally {
            lock.unlock();
        }
    }

    private class DsConnect implements Runnable {
        @Override
        public void run() {
            try {
                // Try to connect to DS on the local machine
                Socket socket = new Socket();
                InetAddress dsAddress = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
                socket.connect(new InetSocketAddress(dsAddress, 1742), CONNECTION_TIMEOUT_MS);

                // Read JSON "{...}".  This is very limited, does not handle
                // quoted "}" or nested {}, but is sufficient for this purpose.
                InputStream ins = new BufferedInputStream(socket.getInputStream());
                ByteArrayOutputStream buf = new ByteArrayOutputStream();

                int b;
                // Throw away characters until {
                while ((b = ins.read()) >= 0 && b != '{' && !isDone()) {}

                // Read characters until }
                while ((b = ins.read()) >= 0 && b != '}' && !isDone()) {
                    buf.write(b);
                }

                if (isDone()) {
                    return;
                }

                String json = buf.toString("UTF-8");

                // Look for "robotIP":12345, and get 12345 portion
                Matcher m = dsPattern.matcher(json);
                if (!m.find()) {
                    log("DS did not provide robotIP", Project.MSG_WARN);
                    return;
                }
                long ip = 0;
                try {
                    ip = Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    if (!isDone()) {
                        log("DS provided invalid IP: \"" + m.group(1) + "\"", e, Project.MSG_WARN);
                    }
                }

                // If zero, the DS isn't connected to the robot
                if (ip == 0) {
                    return;
                }

                // Kick off connection to that address
                InetAddress address = InetAddress.getByAddress(new byte[] {
                    (byte)((ip >> 24) & 0xff),
                    (byte)((ip >> 16) & 0xff),
                    (byte)((ip >> 8) & 0xff),
                    (byte)(ip & 0xff)});
                System.out.println("DS provided " + address.getHostAddress());
                startConnect(address);
            } catch (Exception e) {
                if (!isDone()) {
                    log("could not get IP from DS", e, Project.MSG_WARN);
                }
            } finally {
                finishAttempt("DS");
            }
        }
    }

    private void startDsConnect() {
        startAttempt("DS");
        Thread thr = new Thread(new DsConnect());
        thr.setDaemon(true);
        thr.start();
    }

    // Address resolution can take a while, so we do this in a separate
    // thread, and then kick off a connect attempt on every IPv4 that
    // resolves.
    private class MultiConnect implements Runnable {
        private String host;

        public MultiConnect(String host) {
            this.host = host;
        }

        @Override
        public void run() {
            try {
                for (InetAddress current : InetAddress.getAllByName(host)) {
                    if (!current.isMulticastAddress()) {
                        if (current instanceof Inet4Address) {
                            if (isDone()) {
                                return;
                            }
                            System.out.println("resolved " + host + " to " + current.getHostAddress());
                            startConnect(current);
                        }
                    }
                }
            } catch (Exception e) {
                if (!isDone()) {
                    log("could not resolve " + host, e, Project.MSG_WARN);
                }
            } finally {
                finishAttempt(host);
            }
        }
    }

    private void startConnect(String host) {
        startAttempt(host);
        Thread thr = new Thread(new MultiConnect(host));
        thr.setDaemon(true);
        thr.start();
    }

    private class Connect implements Runnable {
        private InetAddress address;

        public Connect(InetAddress address) {
            this.address = address;
        }

        @Override
        public void run() {
            try {
                tryConnect(address);
            } finally {
                finishAttempt(address.getHostAddress());
            }
        }
    }

    private void startConnect(InetAddress address) {
        startAttempt(address.getHostAddress());
        Thread thr = new Thread(new Connect(address));
        thr.setDaemon(true);
        thr.start();
    }

    private void startConnect(byte[] address) {
        try {
            startConnect(InetAddress.getByAddress(address));
        } catch (UnknownHostException e) {
            log("Error converting address:" + address + ".", e, Project.MSG_WARN);
        }
    }

    private void tryConnect(InetAddress address) {
        try {
            // Configure authentication
            HttpClientContext context = HttpClientContext.create();
            CredentialsProvider provider = null;
            if (username != null) {
                provider = new BasicCredentialsProvider();
                provider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));

                AuthCache authCache = new BasicAuthCache();
                authCache.put(new HttpHost(address.getHostAddress()), new BasicScheme());

                context.setCredentialsProvider(provider);
                context.setAuthCache(authCache);
            }

            // Build path to version file
            URI uri = new URIBuilder()
                .setScheme("http")
                .setHost(address.getHostAddress())
                .setPath("/files/etc/natinst/share/scs_imagemetadata.ini")
                .build();

            // Set timeout options
            RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(CONNECTION_TIMEOUT_MS)
                .setConnectTimeout(CONNECTION_TIMEOUT_MS)
                .setSocketTimeout(CONNECTION_TIMEOUT_MS)
                .build();

            // Try to fetch
            System.out.println("trying " + address.getHostAddress());
            HttpClientBuilder builder = HttpClientBuilder.create()
                .setConnectionManager(new BasicHttpClientConnectionManager())
                .disableAutomaticRetries()
                .disableRedirectHandling();
            if (provider != null) {
                builder = builder.setDefaultCredentialsProvider(provider);
            }
            HttpClient client = builder.build();
            HttpGet get = new HttpGet(uri);
            get.setConfig(config);
            HttpResponse response = client.execute(get, context);

            if (isDone()) {
                return;
            }

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                log("Connected to " + address + ", but no image file.  Is the roboRIO imaged for FIRST?", Project.MSG_WARN);
                return;
            }

            // Look for IMAGEVERSION in file
            InputStream is = response.getEntity().getContent();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                if (isDone()) {
                    return;
                }
                if (!line.startsWith("IMAGEVERSION")) {
                    continue;
                }
                String contents = line.substring(line.indexOf('"', 12));
                Matcher m = imagePattern.matcher(contents);
                if (!m.matches()) {
		    break;
                }

                // Success!
                putResult(address, m.group("year"), m.group("image"));
            }

            log("Connected to " + address + ", but did not get FRC version.  Is the roboRIO imaged for FIRST?", Project.MSG_WARN);
        } catch (Exception e) {
            if (!isDone()) {
                log("Could not connect to " + address.getHostAddress(), e, Project.MSG_WARN);
            }
        }
    }

    private void startAttempt(String loc) {
        lock.lock();
        try {
            attempts.add(loc);
        } finally {
            lock.unlock();
        }
    }

    private void finishAttempt(String loc) {
        lock.lock();
        try {
            attempts.remove(loc);
            if (attempts.isEmpty()) {
                cvDone.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isDone() {
        lock.lock();
        try {
            return done;
        } finally {
            lock.unlock();
        }
    }

    private void putResult(InetAddress target, String imageYear, String image) {
        // return result through global and signal
        lock.lock();
        try {
            if (!done) {
                this.target = target;
                this.imageYear = imageYear;
                this.image = image;
                done = true;
                cvDone.signal();
            }
        } finally {
            lock.unlock();
        }
    }
}
