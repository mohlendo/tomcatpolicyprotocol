package com.cinergix.flash;

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
 * ProtocolHandler that serves a Flash Socket policy file. This is needed for 
 * Flash/Flex applications, that want to use Sockets to talk to the server.
 * You can configure the port (default is 843) and the path of the policy file 
 * ( default allows access from all domain to any port for http over sockets )
 */
public class SocketPolicyProtocolHandler implements ProtocolHandler {
    private final Logger logger = Logger.getLogger(SocketPolicyProtocolHandler.class.getName());
    
    /**
     * Port number to accept the connections. By default it uses port number 843.
     */
    private int port = 843;

    protected static final String EXPECTED_REQUEST = "<policy-file-request/>\0";
    protected static String POLICY_RESPONCE = "<cross-domain-policy><site-control permitted-cross-domain-policies=\"master-only\"/><allow-access-from domain=\"*\" to-ports=\"*\" /></cross-domain-policy>\0";

    private String policyFilePath;

    private Adapter adapter;

    private BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private ThreadPoolExecutor executor = null;
    private ServerSocket serverSocket = null;
    private boolean isShutDownRequested = false;
    
    private int maxThreadPoolSize = 5;
    private int keepAliveTime = 60;

    private void startSocket() {
    	
        Thread thread = new Thread( new Runnable() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                } catch (IOException e) {
                    logger.severe("Could not listen on port: " + port);
                    System.exit(1);
                }

                while (!shutDownRequested()) {
                    Socket clientSocket;
                    try {
                        clientSocket = serverSocket.accept();
                    } catch (IOException e) {
                        logger.severe("Socket accept failed: " + e.getMessage());
                        continue;
                    }
                    try {
                        executor.execute( new WorkerRunnable( clientSocket ) );
                    }
                    catch (RejectedExecutionException e) {
                        logger.severe("Executor rejected execution: " + e.getMessage());
                        try {
                            clientSocket.close();
                        } catch (IOException e1) {
                        	logger.severe("Error closing client: " + e1.getMessage());
                        }
                    }
                }
            }
        }, "SocketLoop" );
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
	        	logger.severe("Error closing server" + e.getMessage());
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

    public int getMaxThreadPoolSize() {
		return maxThreadPoolSize;
	}

	public void setMaxThreadPoolSize(String maxThreadPoolSize) {
		try {
		this.maxThreadPoolSize = Integer.parseInt( maxThreadPoolSize );
		} catch( Exception e ) {
			logger.warning( "Max threadpool size for policy server is not an integer. Default value 5 is set." );
		}
	}

	public int getKeepAliveTime() {
		return keepAliveTime;
	}

	public void setKeepAliveTime(String keepAliveTime) {
		try {
			this.keepAliveTime = Integer.parseInt( keepAliveTime );
		} catch( Exception e ) {
			logger.warning( "Keepalive time for policy server connector is not an integer. Default value 60 is set." );
		}
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
    public void init() {
    	
    	logger.info( "Initializing Flash Socket Policy protocol handler on port: " + port );
        if ( policyFilePath != null ) {
        	
        	logger.info( "Using policy file: " + policyFilePath );
            File file = new File( policyFilePath );
            try{
	            BufferedReader reader = new BufferedReader( new FileReader( file ) );
	            String line;
	            StringBuffer buffy = new StringBuffer();
	            while ( ( line = reader.readLine() ) != null ) {
	                buffy.append( line );
	            }
	            buffy.append("\0");
	            POLICY_RESPONCE = buffy.toString();
	            reader.close();
            } catch( IOException e ) {
            	logger.severe( "Unable to read policyfile from \"" + policyFilePath + "\".\n" + e.getStackTrace() );
            }
        } else {
        	logger.info( "Using default policy file: " + POLICY_RESPONCE );
        }
        
        this.executor = new ThreadPoolExecutor( 1, this.maxThreadPoolSize, this.keepAliveTime, TimeUnit.SECONDS, queue );
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
    Logger logger = Logger.getLogger(WorkerRunnable.class.getName());

    public WorkerRunnable( Socket clientSocket ) {
        this.clientSocket = clientSocket;
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
            
            while ( ( count = in.read( buf ) ) > 0 ) {
            	
                if (count == 23 && new String(buf).startsWith( SocketPolicyProtocolHandler.EXPECTED_REQUEST ) ) {
                    try {
                        out.write( SocketPolicyProtocolHandler.POLICY_RESPONCE.getBytes() );
                        logger.info( "Sent policy" );
                    } catch (IOException ex) {
                    	logger.severe( "Error sending policy file.\n" + ex.getStackTrace() );
                    }

                } else {
                    out.write(buf, 0, count);
                    logger.info( "Ignoring Request. Receied wrong request text " + new String( buf ) );
                }
            }
        } catch (IOException e) {
        	logger.severe("Socket read failed: " + e.getMessage());
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                    logger.info("Flush output and close output stream.");
                }
                if (in != null) {
                    in.close();
                    logger.info("Close input stream.");
                }
            } catch (IOException e) {
            	logger.severe("Error closing input stream and outputstream.\n" + e.getMessage());
            }
        }
    }
}