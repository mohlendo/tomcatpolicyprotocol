/**
 * Copyright (c) 2009, CoreMedia AG, Hamburg. All rights reserved.
 */
package flash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;

/**
 * ProtocolHandler that serves a Flash Socket policy file. This is needed for Flash/Flex applications,
 * that want to use Sockets to talk to the server.
 * You can configure the port (default is 843) and the path of the policy file (default allows access from domain "localhost"
 * to port 80 for http over sockets)
 */
public class SocketPolicyProtocolHandler implements ProtocolHandler {
    private final Logger LOG = Logger.getLogger(SocketPolicyProtocolHandler.class.getName());

    private static final int DEFAULT_PORT = 843;
    private int port = DEFAULT_PORT;

    protected static final String expectedRequest = "<policy-file-request/>\0";

    private String policyFilePath;

    private Adapter adapter;

    private String policy = "<cross-domain-policy><site-control permitted-cross-domain-policies=\"master-only\"/><allow-access-from domain=\"*\" to-ports=\"*\" /></cross-domain-policy>\0";

    private BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 10, 60, TimeUnit.SECONDS, queue);
    private ServerSocket serverSocket = null;
    private boolean isShutDownRequested = false;

    private void startSocket() {
    	
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                } catch (IOException e) {
                    LOG.severe("Could not listen on port: " + port);
                    System.exit(1);
                }

                while (!shutDownRequested()) {
                    Socket clientSocket;
                    try {
                        clientSocket = serverSocket.accept();
                    } catch (IOException e) {
                        LOG.severe("Socket accept failed: " + e.getMessage());
                        continue;
                    }
                    try {
                        executor.execute(new WorkerRunnable(clientSocket, policy));
                    }
                    catch (RejectedExecutionException e) {
                        LOG.severe("Executor rejected execution: " + e.getMessage());
                        try {
                            clientSocket.close();
                        } catch (IOException e1) {
                            LOG.severe("Error closing client: " + e1.getMessage());
                        }
                    }
                }
            }
        }, "SocketLoop");
        thread.start();
    }

    private synchronized boolean shutDownRequested() {
        return this.isShutDownRequested;
    }

    private synchronized void requestShutDown() {
        this.isShutDownRequested = true;
        if( this.serverSocket != null ) {
	        try {
	            this.serverSocket.close();
	        } catch (IOException e) {
	            LOG.severe("Error closing server" + e.getMessage());
	        }
        }
    }

    public void setPolicyFile(String policyFile) {
        this.policyFilePath = policyFile;
    }

    public String getPolicyFile() {
        return this.policyFilePath;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    @Override
    public void init() throws Exception {
        LOG.info(adapter.getClass().getName());
        LOG.info("Initializing Flash Socket Policy protocoll handler on port: " + port);
        if (policyFilePath != null) {
            LOG.info("Using policy file: " + policyFilePath);
            File file = new File(policyFilePath);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            StringBuffer buffy = new StringBuffer();
            while ((line = reader.readLine()) != null) {
                buffy.append(line);
            }
            buffy.append("\0");
            policy = buffy.toString();
        } else {
            LOG.info("Using default policy file: " + policy);
        }
    }

    @Override
    public void start() throws Exception {
        startSocket();
    }

    @Override
    public void pause() throws Exception {

    }

    @Override
    public void resume() throws Exception {

    }

    @Override
    public void destroy() throws Exception {
        requestShutDown();
        executor.shutdown();
    }

	@Override
	public Executor getExecutor() {
		return this.executor;
	}

	@Override
	public boolean isAprRequired() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCometSupported() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCometTimeoutSupported() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isSendfileSupported() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void stop() throws Exception {
		this.isShutDownRequested = true;
	}

}

class WorkerRunnable implements Runnable {
    Socket clientSocket;
    String policy;
    Logger LOG = Logger.getLogger(WorkerRunnable.class.getName());

    public WorkerRunnable(Socket clientSocket, String policy) {
        this.clientSocket = clientSocket;
        this.policy = policy;
    }

    @Override
    public void run() {
        OutputStream out = null;
        InputStream in = null;
        try {
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();
            byte[] buf = new byte[1024];
            int count;
            while (((count = in.read(buf)) > 0)) {
                if (count == 23 && new String(buf).startsWith(SocketPolicyProtocolHandler.expectedRequest)) {
                    try {
                        out.write(policy.getBytes());
                        LOG.info("Sent policy");
                    } catch (IOException ex) {
                        LOG.severe("Error sending policy file");
                    }

                } else {
                    out.write(buf, 0, count);
                    LOG.info("Ignoring Request");
                }
            }
        }
        catch (IOException e) {
            LOG.severe("Socket read failed: " + e.getMessage());
        }
        finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                    LOG.info("Flush output");
                }
                if (in != null)
                    in.close();
            } catch (IOException e) {
                LOG.severe("Error closing in and out: " + e.getMessage());
            }
        }
    }
}