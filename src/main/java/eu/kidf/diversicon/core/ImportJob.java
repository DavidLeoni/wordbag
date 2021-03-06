package eu.kidf.diversicon.core;

import static eu.kidf.diversicon.core.internal.Internals.checkArgument;
import static eu.kidf.diversicon.core.internal.Internals.checkNotNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.annotation.Nullable;

import org.slf4j.event.Level;

import eu.kidf.diversicon.core.LexResPackage;

/**
 * Log for an import of a single LexicalResource
 * 
 * @see ImportConfig
 * @since 0.1.0
 */
public class ImportJob {
   
    private long id;    
    
    private String author;
    private String description;
    
    private String fileUrl;
        
    @Nullable
    private Date startDate;
    @Nullable
    private Date endDate;
    
    private List<LogMessage> logMessages;

    private LexResPackage lexResPackage;

    /**
     * @since 0.1.0
     */
    public ImportJob() {
        this.id = -1;
        this.author = "";
        this.description = "";
        this.fileUrl = "";
        this.lexResPackage = new LexResPackage();
        
        this.startDate = null;
        this.endDate = null;
        this.logMessages = new ArrayList<>();
    }

    
    /**
     * Identifiers used within the resource.
     * 
     * @since 0.1.0
     */
    public LexResPackage getLexResPackage() {
        return lexResPackage;
    }


    /**
     * The name of the author of the import
     * 
     * @since 0.1.0
     */
    public String getAuthor() {
        return author;
    }

    /**
     * See {@link #getAuthor()}
     * 
     * @since 0.1.0
     */
    public void setAuthor(String author) {
        checkNotNull(author);
        this.author = author;
    }

    /**
     * A description of the import.
     * 
     * Why you did the import? For which project was intended? Which problems
     * did you find during the import?
     * 
     * @since 0.1.0
     */
    public String getDescription() {
        return description;
    }

    /**
     * See {@link #getDescription()}
     * 
     * @since 0.1.0
     */
    public void setDescription(String description) {
        checkNotNull(description);
        this.description = description;
    }
          
    
    
    /**
     * 
     * See {@link #getLexResPackage()}
     * 
     * @since 0.1.0
     */
    public void setLexResPackage(LexResPackage lexResPackage) {
        checkNotNull(lexResPackage);
        
        this.lexResPackage = lexResPackage;
    }

    /**
     * The url of the imported file. 
     * 
     * @since 0.1.0
     */
    public String getFileUrl() {
        return fileUrl;
    }


    /**
     * @see #getFileUrl()
     * @since 0.1.0 
     */
    public void setFileUrl(String fileUrl) {
        checkNotNull(fileUrl);
        this.fileUrl = fileUrl;
    }
  


    /**
     * The time when the import started. Can be null if import has not started
     * yet.
     * 
     * @since 0.1.0
     */
    @Nullable
    public Date getStartDate() {
        return startDate;
    }

    /**
     * See {@link #getStartDate()}
     * @since 0.1.0 
     */
    public void setStartDate(@Nullable Date startDate) {
        this.startDate = startDate;
    }

    /**
     * The time when the import ended. Can be null if import has not ended yet.
     * 
     * @since 0.1.0
     */
    @Nullable
    public Date getEndDate() {
        return endDate;
    }

    /**
     * See {@link #getStartDate()}
     * @since 0.1.0 
     */
    public void setEndDate(@Nullable Date endDate) {
        this.endDate = endDate;
    }

    /**
     * The log occurred during the import - most of the times will be just
     * the output logs
     * 
     * <p>
     * NOTE: Logs are *not* stored here by Diversicon Core, applications need to call
     * {@link #addLogMessage(LogMessage)} explicitly. 
     * </p>
     * 
     * @since 0.1.0
     */
    public List<LogMessage> getLogMessages() {
        return logMessages;
    }

    /**
     * Returns the logs occurred during the import having level higher
     * or equal to the specified one.
     *  
     * <p>
     * Most of the times the messages will be just the output logs
     * </p>
     * <p>
     * NOTE: Logs are *not* stored here by Diversicon Core, applications need to call
     * {@link #addLogMessage(LogMessage)} explicitly. 
     * </p>
     * 
     * @since 0.1.0
     */
    public List<LogMessage> getLogMessages(Level level) {
        checkNotNull(level);
        
        List<LogMessage> ret = new ArrayList<>();
        for (LogMessage msg : logMessages){
            if (msg.getLevel().ordinal() >= level.ordinal()){
              ret.add(msg);  
            }
        }
        return logMessages;
    }    
    
    /**
     * See {@link #getLogMessages()}
     * 
     * @since 0.1.0
     */
    public void setLogMessages(List<LogMessage> logMessages) {
        checkNotNull(logMessages);
        this.logMessages = logMessages;
    }

    
    /**
     * Adds a log message.
     * 
     * @since 0.1.0
     */
    public void addLogMessage(LogMessage logMessage){
        this.logMessages.add(logMessage);
    }

    /**
     * @since 0.1.0
     */
    public long getId() {
        return id;
    }

    /**
     * @param id Must be >= -1
     * @since 0.1.0
     */    
    public void setId(long id) {
        checkArgument(id >= -1, "Invalid id, must be >= -1 Found instead " + id);
        this.id = id;
    }


}
