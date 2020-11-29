

package net.sfhome.openremote.protocol.mc401;

import net.sfhome.openremote.protocol.mc401.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;  
import java.time.LocalDate;   
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Scanner;


import org.openremote.agent.protocol.ProtocolExecutorService;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.attribute.MetaItemDescriptor;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.container.ContainerService;
import org.openremote.model.value.*;

import com.fazecast.jSerialComm.*;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;


public class MC401Port {
    public static boolean checkSerialExist(String dev) {
		Path devPath = Paths.get(dev);
		String devName = devPath.getFileName().toString();
		System.out.println("Device: " + devName);
		return Arrays.stream(SerialPort.getCommPorts())
		    .peek(x -> System.out.println(x.getSystemPortName()))
		    .filter(x -> x.getSystemPortName().equalsIgnoreCase(devName))
		    .findFirst()
		    .isPresent();
		
	}

	private final class MessageListener implements SerialPortMessageListener
	{
        private Consumer<String> responseConsumer;
        private AtomicInteger responseLength;
        private Integer reqNumber;
        
        public MessageListener(Consumer<String> responseConsumer, Integer reqNumber) {
            this.responseConsumer = responseConsumer;
            this.responseLength = new AtomicInteger(0);
            this.reqNumber = new Integer(reqNumber);
        }
        
	    @Override
	    public int getListeningEvents() { return SerialPort.LISTENING_EVENT_DATA_RECEIVED; }

	    @Override
	    public byte[] getMessageDelimiter() { return new byte[] { (byte)13 }; }

	    @Override
	    public boolean delimiterIndicatesEndOfMessage() { return true; }

	    @Override
	    public void serialEvent(SerialPortEvent event)
	    {
	        byte[] delimitedMessage = event.getReceivedData();
            String response = new String(delimitedMessage, StandardCharsets.UTF_8);
            Thread.currentThread().setName("MC401Port_SerialEventThread");
	        LOG.fine("Received the following delimited message: " + response);            
            if(MC401Debug.ON) System.out.println("Received the following delimited message: " + response);
            
            //responseConsumer.accept(response);
            if(response.length() > 0) {
                ResponseMessage respMessage = new ResponseMessage(this.reqNumber, response);
                try {
                    responseQueue.put(respMessage);
                } catch(InterruptedException iexp) {
                    LOG.warning("serialEvent of serial port interrupted unexpectedly");                
                }
            }
            this.responseLength.set(response.length());
	    }
        
        public Integer getResponseLength() {
           return this.responseLength.get();   
        }
	}
	
    
    private enum SignalUsage {NOTUSED, ONE, ZERO}
    private String devName;
    private SerialPort serialPort;
    private SignalUsage DTRpin;
    private SignalUsage RTSpin;
    final private AbstractResponseRequest[] msgProcessors = new AbstractResponseRequest[3];
    final private LinkedBlockingQueue<QueueMessage> execQueue = new LinkedBlockingQueue<>();
    final private LinkedBlockingQueue<ResponseMessage> responseQueue = new LinkedBlockingQueue<>();
    final private AtomicInteger rxCounter = new AtomicInteger(0);
    final private AtomicInteger[] rxCounters = new AtomicInteger[3];
    final private AtomicInteger[] txCounters = new AtomicInteger[3];
    final private AtomicInteger[] retryCounters = new AtomicInteger[3];
    final private AtomicInteger retryLimit = new AtomicInteger(3);
    final private String[] internalFieldNames = {"txCounter", "rxCounter", "retryCounter"};
    
    private Thread execThread = null;
    private Thread digestThread = null;
    private Logger LOG = SyslogCategory.getLogger(PROTOCOL, MC401Port.class);
    protected ScheduledExecutorService serviceExecutor;
    private Consumer<ConnectionStatus> protocolStatusUpdater;
    private ConnectionStatus status;
    
    
    MC401Port(ScheduledExecutorService executor, String devName, 
              Boolean dtrUsed, Boolean dtrValue, Boolean rtsUsed, Boolean rtsValue, 
              Integer req1Period, Integer req2Period, Integer req3Period, 
              Consumer<AttributeState> linkedAttributeUpdater,
              Consumer<ConnectionStatus> protocolStatusUpdater) {
        
        if(MC401Debug.ON) LOG.setLevel(Level.FINEST);
        this.serviceExecutor = executor;
        this.protocolStatusUpdater = protocolStatusUpdater;
        String dev = devName;
                
        if(checkSerialExist(dev) ) {
            this.devName = dev;
            if(dtrUsed) {
                this.DTRpin = dtrValue ? MC401Port.SignalUsage.ONE : MC401Port.SignalUsage.ZERO;                
            } else {
                this.DTRpin = MC401Port.SignalUsage.NOTUSED;   
            }
            if(rtsUsed) {
                this.RTSpin = rtsValue ? MC401Port.SignalUsage.ONE : MC401Port.SignalUsage.ZERO;                
            } else {
                this.DTRpin = MC401Port.SignalUsage.NOTUSED;   
            } 

            this.serialPort = SerialPort.getCommPort(this.devName);            
            
            /*
            msgProcessors[0] = new ResponseRequest(1, req1Period, (AttributeRef attrRef, Value newValue) -> 
                        updateLinkedAttribute.accept(new AttributeState(attrRef, newValue)) );
                
            msgProcessors[1] = new ResponseRequest(2, req2Period, (AttributeRef attrRef, Value newValue) -> 
                        updateLinkedAttribute.accept(new AttributeState(attrRef, newValue)) );
            msgProcessors[2] = new ResponseRequest(3, req3Period, (AttributeRef attrRef, Value newValue) -> 
                        updateLinkedAttribute.accept(new AttributeState(attrRef, newValue)) );  
            */
            msgProcessors[0] = new ResponseRequest(1, req1Period, linkedAttributeUpdater );
            msgProcessors[1] = new ResponseRequest(2, req2Period, linkedAttributeUpdater );
            msgProcessors[2] = new ResponseRequest(3, req3Period, linkedAttributeUpdater );  
           
            for(int i=0; i < 3; i++) {
                msgProcessors[i].installQueueServices(serviceExecutor, execQueue);   
                rxCounters[i] = new AtomicInteger(0);
                txCounters[i] = new AtomicInteger(0);
                retryCounters[i] = new AtomicInteger(0);
            }
            
            status = ConnectionStatus.UNKNOWN;
        }
    }
    
    /**
     * Method configures serial port for transmission
     * @return true if port was successuly opened, otherwise false
     */
    public Boolean initPort() {
        if(this.serialPort.openPort()) {
            //Serial port configuration for MC401
            this.serialPort.setComPortParameters(300, 7, SerialPort.TWO_STOP_BITS, SerialPort.EVEN_PARITY);
            //Do not use port signal for flow control, instead configure them as desired in protocolConfiguration
            // They can be usedd to power up opto isolator interface
            this.serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            if(this.DTRpin == MC401Port.SignalUsage.ONE) 
                this.serialPort.setDTR();
            else if(this.DTRpin == MC401Port.SignalUsage.ZERO) 
                this.serialPort.clearDTR();
            if(this.RTSpin == MC401Port.SignalUsage.ONE) 
                this.serialPort.setRTS();
            else if(this.RTSpin == MC401Port.SignalUsage.ZERO) 
                this.serialPort.clearRTS();

             //Check operation mode, if it is valid for delimited response
            this.serialPort.setComPortTimeouts(SerialPort.TIMEOUT_SCANNER, 5000, 1000);//TIMEOUT_READ_BLOCKING   
            return true;
        }
        
        return false;
    }

    /**
     * Method closes serial port 
     * 
     */    
    private synchronized void closePort() {
        this.serialPort.closePort();
    }
    
    public void setLogger(Logger logger) {
      LOG = logger;
        
    }
    /**
     * Method check connection to the MC401 sending request number 1 
     * @return true if any response is received, otherwise false
     */
    public Boolean initConnection() {
        
        byte[] request = {'/','#','1'};
        if(initPort()) {
            if(executeTransmission(request, (String value) ->  System.out.println("Consumer Lambda message: " + value)) > 0) {
                status = ConnectionStatus.CONNECTED;
                //Remove response that we do not like to process from digestQueue if regular thread has not been installed yet
                if(!isDigestRunning()) {
                    responseQueue.poll();
                }
                //MC401 responded leave port open for future transmissions
                return true;   
            } else {
                //Error close port
                closePort(); 
            }
        } 
        return false;
    }
    
    
    /**
     * Method read property 
     * @return string containing system device name, in Linux it would be something like /dev/ttyS0
     */
    public String getDevName() {
       return this.devName;   
    }
    
    public Integer getRxCounter() {
       return rxCounter.get();   
    }
    
    public Integer[][] getStatCounters() {
        Integer[][] counters = new Integer[3][3];
        for(int i = 0; i < 3; i++) {
            counters[0][i] = new Integer(txCounters[i].get());
            counters[1][i] = new Integer(rxCounters[i].get());
            counters[2][i] = new Integer(retryCounters[i].get());
        }
        return counters;   
    }
    
    public Integer getNumberOfLinkedAttribute() {
        Integer sum = new Integer(0);
        for(int i=0; i < 3; i++) {
            sum += msgProcessors[i].getNumberOfLinkedAttributes();   
        }   
        return sum;
    }
    
    private boolean isConnected() {
        if(status == ConnectionStatus.CONNECTED) {
            return true;
        }
        return false;
    }
    private void setProtocolStatus(ConnectionStatus status) {
        this.status = status;
        this.protocolStatusUpdater.accept(status); 
    }
    
    private ConnectionStatus getProtocolStatus() {
        return this.status;
    }
    /**
     * Method terminates periodic thread in all ResponseRequests
     * It is recommended to call this method before disable method to allow calm cleaning of execQueue
     * @return true if all threads was terminated
     */
    
    public boolean terminateRequestTriggers() {
        boolean result = true;
        for(int i = 0; i < 3; i++) {
            result &= msgProcessors[i].terminateScheduledTask();
        }
        return result;
    }
    
    /**
     * Method checking state of executor thread.
     * @return true if thread is not terminated
     */
    public synchronized boolean isExecutorRunning() {
       if(execThread == null) {
           return false;
       }
       Thread.State state = execThread.getState();
       if(state != Thread.State.TERMINATED && state != Thread.State.NEW) {
           return true;   
       }
       return false;
    }
    
   
    
    /**
     * Method executing serial transmission. It sending request to MC401 meter and installs listener
     * responsible for consuming response. Method waits timeout of 1s and gets length of received response
     * in case responseLength is 0 that is response has not been received it waits again, upto 5s.
     * @return length of received response
     */
    public Integer executeTransmission(byte[] request, Consumer<String> respConsumer) {
        Integer responseLength = 0;
        Integer timeoutSec = 10;
        int reqNumber = Character.getNumericValue(request[2]);
        
        if(MC401Debug.ON) System.out.println("********************************************************");
        MC401Port.MessageListener listener = new MessageListener(respConsumer, reqNumber);
        this.serialPort.addDataListener(listener);
        this.serialPort.setBaudRate(300);
        LOG.fine(String.format("request: %c,%c,%c", request[0], request[1], request[2]));
        this.serialPort.writeBytes(request, 3);
        try {
            do {
              Thread.currentThread().sleep(50);
            }while (this.serialPort.bytesAwaitingWrite()>0);
        } catch (InterruptedException exp) {
            LOG.warning("executeTransmission terminated while sending request to MC401"); 
            responseLength = -2;           
            
        }
        //Change baudrate for receiving
        this.serialPort.setBaudRate(1200);
        
        LOG.fine(String.format("Request %d sent! txCounter: %d", reqNumber, txCounters[reqNumber-1].incrementAndGet()));
        //Increment counter of sent requests;
        //txCounters[Character.getNumericValue(request[2])-1].incrementAndGet();
        if(MC401Debug.ON) System.out.println(String.format("Request %d sent! txCounter: %d", reqNumber, txCounters[reqNumber-1].get()));
	    
        try { 
            do {
              Thread.currentThread().sleep(1000); 
              responseLength = listener.getResponseLength();
              LOG.fine(String.format("Response length: %d", responseLength));
              if(MC401Debug.ON) System.out.println(String.format("Response length: %d", responseLength));
                
              timeoutSec--;
            } while((responseLength == 0) && (timeoutSec > 0));
            

            
        } catch (Exception e) {
            LOG.warning("executeTransmission terminated waiting for MC401 responseListener"); 
            if(MC401Debug.ON) System.out.println("Exception waiting for MC401 responseListener");
            responseLength = -1;
        } 
        if(timeoutSec == 0) {
            LOG.warning("executeTransmission timed out waiting for MC401 responseListener"); 
            if(MC401Debug.ON) System.out.println("executeTransmission timed out waiting for MC401 responseListener");
            System.out.println(String.format("TIMEOUT: Response length: %d", responseLength));
        }
        LOG.fine(String.format("Timeout counter: %d\n", timeoutSec));
        this.serialPort.removeDataListener();    
        
        return responseLength;
    }
    
    
    /**
     * Method creates and establish an executor thread that takes request/response info from the execQueue,
     * request transmission, waits to get length of received response. If response lenth does not meet 
     * expected length the request is resent. It happens upto set number of times (3).
     */
    private void installTransmissionExecutor() {
        //Create Runnable
        if(execThread == null) {
            System.out.println("installTransmissionExecutor called first time!!");
            Runnable txExec = () -> {
                while(true) {
                    
                    try {
                        LOG.fine("txExec is waiting for message");
                        if(MC401Debug.ON) System.out.println("txExec is waiting for message");
                        QueueMessage message = execQueue.take();

                        //Check if Executor thread needs to be stopped
                        if(message.isDeathPill()) {
                            LOG.fine("DeathPill detected");
                            if(MC401Debug.ON) System.out.println("DeathPill detected");
                            break;
                        }
                        byte[] request = message.getRequest();
                        int expectedLength = message.getResponseLength();
                        //Consumer<String> responseConsumer = message.getResponseCValue().getValue();
                        int responseLength = 0;
                        int retryCounter = this.retryLimit.get();
                        //Execute transmission. If response is too short than request is repeated upto 3 times.
                        do {
                            //execute transmission of given request
                            responseLength = executeTransmission(request, message);             
                            System.out.println(String.format("retryCounter: %d", retryCounter));
                            
                        }while(responseLength != expectedLength && retryCounter-- > 0); //Failfast, in case of correct response the retryCounter is neiter compared to 0 nor decremented
                         System.out.println(String.format("FINAL retryCounter: %d", retryCounter));
                        //Check if max retry attempts done
                        if(retryCounter == 0) {
                            
                            if(isConnected()) {
                                if(MC401Debug.ON) System.out.println("installTransmissionExecutor: Status disconnected");
                                setProtocolStatus(ConnectionStatus.DISCONNECTED);
                            }
                        } else {
                            //Increment counter of valid responses
                            rxCounter.incrementAndGet();  
                            //Increment counter of valid responses of specific number
                            rxCounters[Character.getNumericValue(request[2])-1].incrementAndGet();
                            
                            if(!isConnected()) {
                                setProtocolStatus(ConnectionStatus.CONNECTED); 
                            }
                        }
                        if(retryCounter != this.retryLimit.get()) {
                            //Increment counter of retries for specific request
                            retryCounters[Character.getNumericValue(request[2])-1].addAndGet(this.retryLimit.get() - retryCounter);
                        }
                    }
                    catch(InterruptedException exp) {
                       LOG.warning("Transmission Executor terminated without a deathPill and grace period!");
                    }


                }   
                LOG.fine("ExecutorThread terminated gracefully");
                if(MC401Debug.ON) System.out.println("ExecutorThread terminated gracefully");

            };

            //create thread for Runnable
            execThread = new Thread(txExec);
            execThread.setName("MC401Port_Executor");
        } else {
            System.out.println("installTransmissionExecutor called again!!");
        }
        
        if(!isExecutorRunning()) {
            execThread.start();
        }
        LOG.fine(String.format("Transmission exec thread installed")); 
    }

    /**
     * Method creates and empty entry and add it to the queue. The executing thread should interpret it as a dead pill and terminate itself.
     * There is timeout for gracefull termination of executing thread
     */
    private void poisonTransmissionExecutor() {
        if(execThread == null)
            return;
        try {
            execQueue.put(new QueueMessage());
            System.out.println("DeathPill added to the execQueue");
        }
        catch(InterruptedException exp) {
            LOG.warning("Poisoning transmissionExecutor was terminated prematurely while waiting for execQueue");
        }

        try {
            Integer timeOutSec =10;
            do {
                Thread.sleep(1000);
                timeOutSec--;
            } while((execThread.getState() != Thread.State.TERMINATED) && (timeOutSec > 0));
            if(timeOutSec == 0) {
                LOG.warning("Waiting for poisoning of transmissionExecutor timed out");                    
            }
        }
        catch(InterruptedException exp) {
            LOG.warning("Poisoning transmissionExecutor terminated without waiting");
        }        
    }

    
    /**
     * Method checking state of digest thread. 
     * @return true if thread is not terminated
     */
    public synchronized boolean isDigestRunning() {
       if(digestThread == null) {
           return false;
       }
       Thread.State state = digestThread.getState();
       if(state != Thread.State.TERMINATED && state != Thread.State.NEW) {
           return true;   
       }
       return false;
    }    
        
    /**
     * Method creates and establish a digest thread that takes response from the responseQueue,
     * check response length and process it.
     */
    private void installResponseDigest() {
        //Create Runnable
        if(digestThread == null) {
            Runnable respExec = () -> {
                while(true) {                    
                    try {
                        LOG.fine("respExec is waiting for message");
                        if(MC401Debug.ON) System.out.println("respExec is waiting for message");
                        ResponseMessage message = responseQueue.take();
                        
                        //Check if it is a killing pill
                        if(message.isDeathPill()) {
                            LOG.fine("DeathPill detected");
                            if(MC401Debug.ON) System.out.println("DeathPill detected");
                            break;
                        }
                        Integer request = new Integer(message.getRequestNumber());
                        String  response = new String(message.getResponse());
                        if(response.length() == msgProcessors[request-1].getResponseLength()) {
                            msgProcessors[request-1].accept(response); 
                        }
                    }
                    catch(InterruptedException exp) {
                       LOG.warning("Response Digest terminated without a deathPill and grace period!");
                    }


                }   
                LOG.fine("DigestThread terminated gracefully");
                if(MC401Debug.ON) System.out.println("DigestThread terminated gracefully");

            };

            //create thread for Runnable
            digestThread = new Thread(respExec);
            digestThread.setName("MC401Port_DigestThread");
        } else {
            LOG.fine("installResponseDigest called again!!");            
        }
        
        if(!isDigestRunning()) {
            digestThread.start();
        }
        LOG.fine(String.format("Digest thread installed")); 
    }
    
    
    /**
     * Method creates and empty entry and add it to the responseQueue. The executing thread should interpret it as a dead pill and terminate itself.
     * There is timeout for gracefull termination of executing thread
     */
    private void poisonResponseDigest() {
        if(digestThread == null)
            return;
        try {
            responseQueue.put(new ResponseMessage());
            System.out.println("DeathPill added to the responseQueue");
        }
        catch(InterruptedException exp) {
            LOG.warning("Poisoning responseDigest was terminated prematurely while waiting for responseQueue");
        }

        try {
            Integer timeOutSec =10;
            do {
                Thread.sleep(1000);
                timeOutSec--;
            } while((digestThread.getState() != Thread.State.TERMINATED) && (timeOutSec > 0));
            if(timeOutSec == 0) {
                LOG.warning("Waiting for poisoning of responseDigest timed out");                    
            }
        }
        catch(InterruptedException exp) {
            LOG.warning("Poisoning responseDigest terminated without waiting");
        }        
    }
    

    /**
     * Method links given attribute to specific response processor
     * 
     */
    public void linkAttribute(AttributeRef attributeRef, String fieldName) {
        //Check if it is attempt to link local statistical counter
        //TODO: finish implementation
        if(Arrays.stream(internalFieldNames).anyMatch(value -> fieldName.startsWith(value))) {
            String[] elements = fieldName.split("_");
            if(elements.length == 2) {
                //int reqNumber
                 //   HashMap<AttributeReference, String>
                }
            LOG.fine(String.format("MC401Port: linking local attribute, fieldName: %s", fieldName));
            if(MC401Debug.ON) System.out.println(String.format("MC401Port: linking local attribute, fieldName: %s", fieldName));
        
        } else {
            int reqNumber = ResponseRequest.getRequestNumber(fieldName);
            //System.out.println(String.format("Field name: %s -> request number: %d", fieldName, reqNumber));
            //get the right ResponseRequest processor
            AbstractResponseRequest processor = msgProcessors[reqNumber-1];

            LOG.fine(String.format("MC401Port: linking attribute, fieldName: %s, request: %d", fieldName, reqNumber));
            if(MC401Debug.ON) System.out.println(String.format("MC401Port: linking attribute, fieldName: %s, request: %d", fieldName, reqNumber));

            //link attribute to the right processor
            processor.linkAttribute(attributeRef, fieldName);
        }
            
        
    }
   
    /**
     * Method unlinks given attribute from response processor
     *
     */
    public void unLinkAttribute(AttributeRef attributeRef, String fieldName) {
        
        int reqNumber = ResponseRequest.getRequestNumber(fieldName);
        //System.out.println(String.format("Field name: %s -> request number: %d", fieldName, reqNumber));
        //get the right ResponseRequest processor
        AbstractResponseRequest processor = msgProcessors[reqNumber-1];
        //unlink attribute from field in response processor and kill trigger if there id not
        processor.unlinkAttribute(attributeRef, fieldName);
            
    }

        
    public void enable() {
        if(!System.getProperty("net.sfhome.openremote.protocol.mc401.TestUtils.disableQueueClearing", "false").equals("true")) {
            //Clear queues
            execQueue.clear();
            responseQueue.clear();
        }
        if(execThread == null) {
          System.out.println("Enable - installTransmissionExecutor");
          //Create executor thread
          installTransmissionExecutor();          
        }
        if(digestThread == null) {
          System.out.println("Enable - installResponseDigest");
          //Create executor thread
          installResponseDigest();          
        }
        //Clear statistical counters
        for(int i=0; i < 3; i++) {
            rxCounters[i].set(0);
            txCounters[i].set(0);
            retryCounters[i].set(0);
        }
        
        //if(MC401Debug.ON) System.out.println("MC401Port.enable(): Status Disconnected");
        //setProtocolStatus(ConnectionStatus.DISCONNECTED);
        
    }
    
    public void disable() {
        disable(true, false);
    }
        
    public void disable(boolean clearExecQueueImmediately, boolean clearDigestQueueImmediately) {
        System.out.println("Disabling port!");
        
        setProtocolStatus(ConnectionStatus.DISCONNECTING);
        
        
        //Kill serial traffic executor
        if(clearExecQueueImmediately) {
            execQueue.clear();
        }
        //poison execQueue to kill execThread
        if(isExecutorRunning()) {
            poisonTransmissionExecutor();
        }
        
        //Clear any message added to the execQueue after executor thread is terminated
        execQueue.clear();
        
        if(clearDigestQueueImmediately) {
            responseQueue.clear();               
        } else {
            Integer timeoutSec = 10;
            //Wait till all responses are processed
            while(responseQueue.size() > 0 && timeoutSec > 0) {
                try {
                    Thread.sleep(100); 
                } catch(InterruptedException iexp) {
                    LOG.warning("Waiting for respponseQueue empting terminated without waiting for all responses being processed");   
                }
                timeoutSec--;
            }
            if(responseQueue.size() > 0 && timeoutSec == 0) {
                LOG.warning("The responseQueue was not emptied within in the time limit");   
         
            }
        }
        //only now, after cleaning responseQueue it can be poisond to kill digestThread
        if(isDigestRunning()) {
            poisonResponseDigest();
        }
        //Close serial port
        closePort();
        
        setProtocolStatus(ConnectionStatus.DISABLED);
        
    }    
    
    /**
     * Manual executing request and receiving response, it bypasses the whole engine of MC401Port 
     * 
     */
    public Boolean manualCheck() {
        
        SerialPort port;
        port = SerialPort.getCommPort(this.getDevName());
        try {
            if(port.openPort()) {
                port.setComPortParameters(300, 7, SerialPort.TWO_STOP_BITS, SerialPort.EVEN_PARITY);
                //Do not use port signal for flow control, instead configure them as desired in protocolConfiguration
                // They can be usedd to power up opto isolator interface
                port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

                System.out.println("Serial port open: " + this.getDevName());
                port.setComPortTimeouts(SerialPort.TIMEOUT_SCANNER, 5000, 1000);//TIMEOUT_READ_BLOCKING
                port.clearDTR();
                port.setRTS();

                byte[] request = {'/','#','1'};
                port.writeBytes(request, 3);
                do {
                    Thread.sleep(10);
                }while (port.bytesAwaitingWrite()>0);

                port.setBaudRate(1200);

                InputStream stream = port.getInputStream();
                Scanner reader = new Scanner(stream);
                int value;
                do {
                  Thread.sleep(10);
                } while (port.bytesAvailable() < 80);
                System.out.format("Received bytes: %d\n", port.bytesAvailable());

                System.out.format("1.  Received Energy: %5.2f\n", (float)reader.nextInt()/100); 
                System.out.format("2.  Received Volume: %5.2f\n", (float)reader.nextInt()/100);
                System.out.format("3.  Received Hours: %d\n", reader.nextInt());
                System.out.format("4.  Received T1: %3.2f\n", (float)reader.nextInt()/100);
                System.out.format("5.  Received T2: %3.2f\n", (float)reader.nextInt()/100);
                System.out.format("6.  Received T1-T2: %3.2f\n", (float)reader.nextInt()/100);
                System.out.format("7.  Received Power: %d\n", reader.nextInt());
                System.out.format("8.  Received Flow: %d\n", reader.nextInt());
                System.out.format("9.  Received Peak power: %d\n", reader.nextInt());
                System.out.format("10. Received Info: %d\n", reader.nextInt());

                System.out.println("");
                System.out.println("Finished ");
                port.closePort();

                return true;
            } else {
               System.out.println("Unable to open serial port: COM24");
            }
        } catch(Exception exp) {
            System.out.println("Exception in manualCheck function: " + exp.getMessage());
        }
        return false;
            
    }
    
    /**
     * Method for tests
     * @return size of the execQueue
     */
    public int debugGetQueueSize() {
        if(MC401Debug.ON) {
            return execQueue.size();               
        }
        return 0;        
    }

    /**
     * Method for tests
     * @return size of the digestQueue
     */    
    public int debugGetDigestQueueSize() {
        if(MC401Debug.ON) {
            return responseQueue.size();               
        }
        return 0;        
    }
    
    /**
     * Method for tests, injects message to the execQueue, uses provided consumer to accept serial response
     * @param consumer of response 
     * @return true is message was posted, otherwise false
     */
    public Boolean debugSimpleInjection(Consumer<String> consumer) {
        if(MC401Debug.ON) {
            try {
                byte[] request = {'/','#','1'};
                QueueMessage message = new QueueMessage(request, 81, consumer);
                execQueue.put(message);

            } catch(Exception exp) {
                System.out.println("Exception in debugSimpleInjection method: " + exp.getMessage());
            }
            return true;

        }
        
        return false;
    }
    
    
    /**
     * Method for tests, injects message to the execQueue, uses regular messageProcessor consumer
     * @return true is message was posted, otherwise false
     */
    public Boolean debugInjection() {
        if(MC401Debug.ON) {
            try {
                QueueMessage message = new QueueMessage(msgProcessors[0].getRequest(), msgProcessors[0].getResponseLength(), msgProcessors[0]);
                execQueue.put(message);

            } catch(Exception exp) {
                System.out.println("Exception in debugInjection function: " + exp.getMessage());
            }
            return true;

        }
        
        return false;
    }
    
    
    
    /**
     * Method check to the MC401 using message
     * @return true if any response is received, otherwise false
     */
    public Boolean debugCheckMessage(QueueMessage message) {
        if(MC401Debug.ON) {

            byte[] request = {'/','#','1'};
            initPort();
            if(executeTransmission(message.getRequest(), message) > 0) {
                closePort(); 
                return true;               
            } else {
                closePort(); 
            }
        }
        return false;
    }
    
    /**
     * Method terminates all threads requesting transmission on regular basis
     * 
     */
 /*   private void killAllTransmissionTriggers() {
        for (int i=1; i < 4; i++) {
            killTransmissionTrigger(i);
        }
        
    }
   */ 
    
    /**
     * Method to generate attributes template for autodiscovery interface in MC401Protocol
     * @return List<AssetAttribute> to be inserted to OpenRemote Asset
     */
    public List<AssetAttribute> getAssetAttributeTemplate(Integer reqNumber, MetaItemDescriptor mustHaveMetaItem)  throws IllegalArgumentException {
        if(reqNumber < 1 || reqNumber > 3) {
            throw new IllegalArgumentException(String.format(
                "reqNumber argument in getAssetAttributeTemplate exceeded allowed values of <1,%d>, having value of %d\n", 
                3, reqNumber)); 
        }
        
        return msgProcessors[reqNumber-1].getAssetAttributeTemplate(mustHaveMetaItem);       
    }
    
    /**
     * Method used purely by tests to
     * 
     */
    public void installDebugTransmissionExecutor() {
        
        
    }
    /*
    public void checkResponseConsumer(Integer reqNumber) {
        AbstractResponseRequest processor = msgProcessors[reqNumber-1];
        
        int expectedLength = processor.getResponseLength();
                
        int responseLength = executeTransmission(processor.getRequest(), processor);
        System.out.println("ResponseLength: " + responseLength);
        
    }
*/
}
