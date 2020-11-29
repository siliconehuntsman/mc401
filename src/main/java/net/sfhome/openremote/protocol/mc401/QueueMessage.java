

package net.sfhome.openremote.protocol.mc401;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.function.Consumer;

import org.openremote.model.value.*;


public class QueueMessage implements Consumer<String> {
    private byte[] request;
    private Integer responseLength = 0;
    private Consumer<String> responseConsumer = null;

   
    /**
     * Creator used for 
     */
    public QueueMessage(byte[] request) {
        this.request = request;
        this.responseLength = 0;
        this.responseConsumer = null;        
    }  

    /**
     * Creator used for deathpill
     */
    public QueueMessage() {
        this.request = null;
        this.responseLength = 0;
        this.responseConsumer = null;        
    }  
    
    public QueueMessage(byte[] request, AbstractMap.SimpleEntry<Integer, Consumer<String>> responseInfo) {
        this.request = request;
        this.responseLength = responseInfo.getKey();
        this.responseConsumer = responseInfo.getValue();        
        System.out.println(String.format("Message for reqNum: %c created", request[2]));
    }  
    
    public QueueMessage(byte[] request, Integer responseLength, Consumer<String> responseConsumer) {
        this.request = request;
        this.responseLength = responseLength;
        this.responseConsumer = responseConsumer;
        
    }
    public byte[] getRequest() {
        return this.request;
    }
    
    public Integer getResponseLength() {
        return this.responseLength;
    }
    
    public void accept(String response) {
        if(this.responseConsumer != null) {
            this.responseConsumer.accept(response);
        }
    }
    
    public boolean isDeathPill() {
        return this.request == null ? true : false;
    }
    public void changeLength(int value) {
        this.responseLength = value;  
        
    }
    
}