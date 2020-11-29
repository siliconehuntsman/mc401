

package net.sfhome.openremote.protocol.mc401;

import net.sfhome.openremote.protocol.mc401.QueueMessage;
import net.sfhome.openremote.protocol.mc401.*;

import java.lang.Exception;
import java.lang.NullPointerException;
import java.time.format.DateTimeFormatter;  
import java.time.LocalDate;   
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.Map;

//import javafx.util;

import org.openremote.agent.protocol.ProtocolExecutorService;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.attribute.MetaItemDescriptor;
import org.openremote.model.attribute.MetaItemType;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.value.*;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

abstract class AbstractResponseRequest implements Consumer<String> {

      
  
    private Integer requestNumber;
    private Integer requestPeriod;
    protected List<ResponseField> fields;
    
    private Integer responseLength;
    private Integer numberOfLinkedAttributes;
    private ScheduledExecutorService serviceExecutor;
    private LinkedBlockingQueue<QueueMessage> execQueue;
    private ScheduledFuture scheduledFuture;
    final private Logger LOG = SyslogCategory.getLogger(PROTOCOL, AbstractResponseRequest.class);
    
    public AbstractResponseRequest(Integer requestNumber, Consumer<AttributeState> attributeUpdater) { //BiConsumer<AttributeRef, Value> attributeUpdater) {
        serviceExecutor = null;
        execQueue = null;   
        scheduledFuture = null;

        if((requestNumber > 0) && (requestNumber <=3)) {
            this.requestNumber = requestNumber;

            fields = Collections.synchronizedList(new ArrayList<>());
            generateFields();
            this.responseLength = fields.stream()
                .mapToInt(field -> field.getLength() + 1) // additional seperating space for each field
                .sum() + 1; //Include ending '/r'

            fields.forEach(field -> field.setUpdater(attributeUpdater));
            //Initilize number of linked attributes counter
            numberOfLinkedAttributes = 0;
        } else { 
            //Other request numbers are not supported, specifically numer 5 to read monthly data
            this.requestNumber = 0;
            this.responseLength = 0;
        }
    }
  

    abstract void generateFields();
    

    public void accept(String response) {
        synchronized(fields) {
            Iterator<ResponseField> fieldIterator = fields.iterator();

            List<String> respArray = Collections.synchronizedList(Arrays.asList(response.split(" ")));
            synchronized(respArray) {
                Iterator<String> respIterator = respArray.iterator();

                while(respIterator.hasNext() && fieldIterator.hasNext()) {
                    ResponseField field = fieldIterator.next();
                    field.accept(respIterator.next());        
                    //respIterator.next();
                }
                if(respIterator.hasNext() || fieldIterator.hasNext()) {
                   //throw Exception incorrect number of response elements
                } 
            }
        }
    }
    

        
    
    protected Integer getRequestNumber() {
        return this.requestNumber;
    }
    
        
    protected void setRequestPeriod(Integer requestPeriod) {
      this.requestPeriod = requestPeriod; 
    }
    
    public Integer getRequestPeriod() {
      return this.requestPeriod; 
    }
    
    //Funtion returns byte array string to be sent to MC401 Meter
    public byte[] getRequest() {
        byte reqCode;
        switch(requestNumber) {
            case 1: reqCode = '1';break;
            case 2: reqCode = '2';break;
            case 3: reqCode = '3';break;
            default: reqCode = '0' ;break;
        }
        byte[] request = {'/','#',reqCode};
        return request;     
    }
    
    //Length of expected response
    public Integer getResponseLength() {
       return this.responseLength;
    }
    
    public Integer getNumberOfLinkedAttributes() {
        return numberOfLinkedAttributes;   
    }
    
    //THIS PROBABLY CAN BE REMOVED and only Consumer<String> - accept to be used, just check if inserting element to the queue inserts reference or make copy
    //Get pair corresponding to expected response and consumer to digest this response
    // SimpleEntry.Key - gives expected lenth of response to drop too short responses
    // SimpleEntry.Value - Consumer accepting response string, it already needs to have correct length
    //                       the consumer walks through all substrings in the response delimited by SPACE and
    //                       through fields of given response and calls field update consumer
 /*   public AbstractMap.SimpleEntry<Integer, Consumer<String>> getResponseInfo() {
       return new AbstractMap.SimpleEntry<>(this.getResponseLength(), 
                                            (String response) -> {
                                                System.out.println("Analysing lambda");
                                                 synchronized(fields) {
                                                    Iterator<ResponseField> fieldIterator = fields.iterator();

                                                    List<String> respArray = Arrays.asList(response.split(" "));
                                                    Iterator<String> respIterator = respArray.iterator();

                                                    while(respIterator.hasNext() && fieldIterator.hasNext()) {
                                                      ResponseField field = fieldIterator.next();
                                                        field.accept(respIterator.next());            
                                                    }
                                                    if(respIterator.hasNext() || fieldIterator.hasNext()) {
                                                       //throw Exception incorrect number of response elements
                                                    }
                                                 }
                                             }
                                          );   
        
    }
    */

    public int linkAttribute(AttributeRef attributeRef, String fieldName) {
        synchronized(fields) {
            ResponseField respField = fields.stream()
               .filter(field -> fieldName.equalsIgnoreCase(field.getFieldName()))
               .findFirst()
               .orElseThrow(IllegalArgumentException::new);
            
            respField.addLinkedAttribute(attributeRef);                                                            
        }
        if(numberOfLinkedAttributes++ == 0) {
            installScheduledTask();
        }
        LOG.fine(String.format("ResponseRequest: linking attribute, number: %d", numberOfLinkedAttributes));
        if(MC401Debug.ON) System.out.println(String.format("ResponseRequest: linking attribute, number: %d", numberOfLinkedAttributes));
        return numberOfLinkedAttributes;
    }
    
    public int unlinkAttribute(AttributeRef attributeRef, String fieldName) {
        synchronized(fields) {
            Optional<ResponseField> respField = fields.stream()
               .filter(field -> fieldName.equalsIgnoreCase(field.getFieldName()))
               .findFirst();
            
            respField.orElseThrow(IllegalArgumentException::new)
                .removeLinkedAttribute(attributeRef);
            
            
        }        
                
        if(--numberOfLinkedAttributes == 0) {
            terminateScheduledTask();
        }
        return numberOfLinkedAttributes;
    }
    
    /**
     * peepRegedAttr funtion allows to list how many attributes has been registered with this instance
     *  It is used for debug only
     */
    
    public void peepRegedAttr() {
        synchronized(fields) {
            Iterator<ResponseField> fieldIterator = fields.iterator();
            while(fieldIterator.hasNext()) {
              ResponseField field = fieldIterator.next();
              field.peepNumOfAttr();
              //System.out.printf("There are %d attributes registered for field name: %s\n", linkedAttributeRefs.size(), this.getFieldName());
            }
        }
    }
    
    /**
     * Method generates asset attributes for autodiscovery
     * @return List of AssetAttributes for serviced response type
     */    
    public List<AssetAttribute> getAssetAttributeTemplate(MetaItemDescriptor mustHaveMetaItem) {
        ArrayList<AssetAttribute> attributeTemplate = new ArrayList<>();
        synchronized(fields) {
            Iterator<ResponseField> fieldIterator = fields.iterator();
            while(fieldIterator.hasNext()) {
                ResponseField field = fieldIterator.next();
                AssetAttribute attribute = new AssetAttribute(field.getFieldName(), field.getType());
                //Create and add MetaItemsto the attribute
                MetaItem fieldNameMeta;
                fieldNameMeta = new MetaItem(mustHaveMetaItem);
                fieldNameMeta.setValue(Values.create(field.getFieldName()));                               
                attribute.setMeta(
                    fieldNameMeta,
                    new MetaItem(MetaItemType.LABEL, Values.create(field.getLabel())),
                    new MetaItem(MetaItemType.READ_ONLY, Values.create(true))
                );
                
                attributeTemplate.add(attribute);
            }
        }
        return attributeTemplate;
        
    }
    
    //*****************************************************************************************************************
    //  Queue and thread related elements
    
    /**
     * Method passess system level services to this ResponseRequest instance
     * @param executor to install thread executed on timely manner to post request to the
     * @param queue where request messages will be inserted. It shall be thread save implementation like LinkedBlockingQueue
     * @throws NullPointerException when at least one of arguments is null
     */
    public void installQueueServices(ScheduledExecutorService executor, 
                                     LinkedBlockingQueue<QueueMessage> queue) throws NullPointerException {
        if(executor == null || queue == null) {
           throw new NullPointerException(String.format("Null argument detected: $s!", executor == null ? "executor" : "queue"));
        }
        serviceExecutor = executor;
        execQueue = queue;   
        
    } 
    
    private void postMessage() {
       // QueueMessage message = new QueueMessage(getRequest(), getResponseInfo());
        QueueMessage message = new QueueMessage(getRequest(), getResponseLength(), this);
        try {
            execQueue.put(message); 
        }
        catch(InterruptedException intExp) {
            LOG.warning(String.format("Request message posting interrupted for request number: %d", getRequestNumber()));
        }
    }
    
    private void installScheduledTask() {
               
        if(System.getProperty("MJX_IsolationTestMode", "false").equals("true") )
           return;
        
        if(serviceExecutor == null) {
            System.out.println("serviceExecutor has not been initilized!");
            throw new NullPointerException("serviceExecutor has not been initilized!");
        }
        if(execQueue == null) {
            System.out.println("execQueue has not been initilized!");
            throw new NullPointerException("execQueue has not been initilized!");
        }
        
        Runnable txTrigger = () -> {
            
           postMessage();   
        };
        
        LOG.fine("ResponseRequest: installing posting task");
        if(MC401Debug.ON) System.out.println("ResponseRequest: installing posting task");
        
        Thread trgThread = new Thread(txTrigger);
        trgThread.setName(String.format("MC401Port_RequestTriggerThread_%1d", getRequestNumber()));
        
        //ProtocolExecutorService protocolExecutor = (ProtocolExecutorService) serviceExecutor;
		scheduledFuture = serviceExecutor.scheduleAtFixedRate(trgThread, 1, getRequestPeriod(), TimeUnit.SECONDS);
        
    }
    
    /**
     * Terminates scheduled thread that post request in regular basis
     *  @return false when thread was already terminated or termination of thread was unsuccessfulm true overwise
     */     
    public boolean terminateScheduledTask() {
        if(System.getProperty("MJX_IsolationTestMode", "false").equals("true") ) 
            return false;
        
        if(scheduledFuture != null) {
            return scheduledFuture.cancel(false);
        } else {
            return false;
        }
    }


}