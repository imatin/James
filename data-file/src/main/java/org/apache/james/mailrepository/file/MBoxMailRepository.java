/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

/* TODO:
 *
 * 1. Currently, iterating through the message collection does not
 *    preserve the order in the file.  Change this with some form of
 *    OrderedMap.  There is a suitable class in Jakarta Commons
 *    Collections.
 *
 * 2. Optimize the remove operation.
 *
 * 3. Don't load entire message into memory.  This would mean computing
 *    the hash during I/O streaming, rather than loading entire message
 *    into memory, and using a MimeMessageWrapper with a suitable data
 *    source.  As a strawman, the interface to MessageAction would
 *    carry the hash, along with a size-limited stream providing the
 *    message body.
 *
 * 4. Decide what to do when there are IDENTICAL messages in the file.
 *    Right now only the last one will ever be processed, due to key
 *    collissions.
 *
 * 5. isComplete()  - DONE.
 *
 * 6. Buffered I/O. - Partially done, and optional.
 *
 */

package org.apache.james.mailrepository.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.james.core.MailImpl;
import org.apache.james.lifecycle.api.Configurable;
import org.apache.james.lifecycle.api.LogEnabled;
import org.apache.james.mailrepository.api.MailRepository;
import org.apache.mailet.Mail;
import org.slf4j.Logger;

/**
 * Implementation of a MailRepository using UNIX mbox files.
 * 
 * <p>
 * Requires a configuration element in the .conf.xml file of the form:
 * 
 * <pre>
 *  &lt;repository destinationURL="mbox://&lt;directory&gt;"
 *             type="MAIL"/&gt;
 * </pre>
 * 
 * &lt;directory&gt; is where the individual mbox files are read from/written to.
 * </p>
 * <p>
 * Type can ONLY be MAIL (SPOOL is NOT supported)
 * </p>
 * 
 * <p>
 * Requires a logger called MailRepository.
 * 
 * <p>
 * Implementation notes:
 * <p>
 * This class keeps an internal store of the mbox file When the internal mbox
 * file is updated (added/deleted) then the file will be re-read from disk and
 * then written back. This is a bit inefficent but means that the file on disk
 * should be correct.
 * <p>
 * The mbox store is mainly meant to be used as a one-way street. Storing new
 * emails is very fast (append to file) whereas reading them (via POP3) is
 * slower (read from disk and parse). Therefore this implementation is best
 * suited to people who wish to use the mbox format for taking data out of James
 * and into something else (IMAP server or mail list displayer)
 */

public class MBoxMailRepository implements MailRepository, LogEnabled, Configurable {

    static final SimpleDateFormat dy = new SimpleDateFormat("EE MMM dd HH:mm:ss yyyy", Locale.US);
    static final String LOCKEXT = ".lock";
    static final String WORKEXT = ".work";
    static final int LOCKSLEEPDELAY = 2000; // 2 second back off in the event of
                                            // a problem with the lock file
    static final int MAXSLEEPTIMES = 100; //
    static final long MLISTPRESIZEFACTOR = 10 * 1024; // The hash table will be
                                                      // loaded with a initial
                                                      // capacity of
                                                      // filelength/MLISTPRESIZEFACTOR
    static final long DEFAULTMLISTCAPACITY = 20; // Set up a hashtable to have a
                                                 // meaningful default

    /**
     * Whether line buffering is turned used.
     */
    private static boolean BUFFERING = true;

    /**
     * Whether 'deep debugging' is turned on.
     */
    private static final boolean DEEP_DEBUG = true;

    /**
     * The internal list of the emails The key is an adapted MD5 checksum of the
     * mail
     */
    private Hashtable<String, Long> mList = null;
    /**
     * The filename to read & write the mbox from/to
     */
    private String mboxFile;

    private boolean fifo;

    /**
     * A callback used when a message is read from the mbox file
     */
    public interface MessageAction {
        boolean isComplete(); // *** Not valid until AFTER each call to
                              // messageAction(...)!

        MimeMessage messageAction(String messageSeparator, String bodyText, long messageStart);
    }

    /**
     * The repository configuration
     */
    private HierarchicalConfiguration configuration;

    private Logger logger;

    public void setLog(Logger logger) {
        this.logger = logger;
    }

    public void configure(HierarchicalConfiguration configuration) throws ConfigurationException {
        this.configuration = configuration;
        String destination;
        this.mList = null;
        BUFFERING = configuration.getBoolean("[@BUFFERING]", true);
        fifo = configuration.getBoolean("[@FIFO]", false);
        destination = configuration.getString("[@destinationURL]");
        if (destination.charAt(destination.length() - 1) == '/') {
            // Remove the trailing / as well as the protocol marker
            mboxFile = destination.substring("mbox://".length(), destination.lastIndexOf("/"));
        } else {
            mboxFile = destination.substring("mbox://".length());
        }

        if (getLogger().isDebugEnabled()) {
            getLogger().debug("MBoxMailRepository.destinationURL: " + destination);
        }

        String checkType = configuration.getString("[@type]");
        if (!(checkType.equals("MAIL") || checkType.equals("SPOOL"))) {
            String exceptionString = "Attempt to configure MboxMailRepository as " + checkType;
            if (getLogger().isWarnEnabled()) {
                getLogger().warn(exceptionString);
            }
            throw new ConfigurationException(exceptionString);
        }
    }

    protected Logger getLogger() {
        return logger;
    }

    /**
     * Convert a MimeMessage into raw text
     * 
     * @param mc
     *            The mime message to convert
     * @return A string representation of the mime message
     * @throws IOException
     * @throws MessagingException
     */
    private String getRawMessage(MimeMessage mc) throws IOException, MessagingException {

        ByteArrayOutputStream rawMessage = new ByteArrayOutputStream();
        mc.writeTo(rawMessage);
        return rawMessage.toString();
    }

    /**
     * Parse a text block as an email and convert it into a mime message
     * 
     * @param emailBody
     *            The headers and body of an email. This will be parsed into a
     *            mime message and stored
     */
    private MimeMessage convertTextToMimeMessage(String emailBody) {
        // this.emailBody = emailBody;
        MimeMessage mimeMessage = null;
        // Parse the mime message as we have the full message now (in string
        // format)
        ByteArrayInputStream mb = new ByteArrayInputStream(emailBody.getBytes());
        Properties props = System.getProperties();
        Session session = Session.getDefaultInstance(props);
        try {
            mimeMessage = new MimeMessage(session, mb);

        } catch (MessagingException e) {
            getLogger().error("Unable to parse mime message!", e);
        }

        if (mimeMessage == null && getLogger().isDebugEnabled()) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Mime message is null");
            getLogger().debug(logBuffer.toString());
        }

        /*
         * String toAddr = null; try { // Attempt to read the TO field and see
         * if it errors toAddr =
         * mimeMessage.getRecipients(javax.mail.Message.RecipientType
         * .TO).toString(); } catch (Exception e) { // It has errored, so time
         * for plan B // use the from field I suppose try {
         * mimeMessage.setRecipients(javax.mail.Message.RecipientType.TO,
         * mimeMessage.getFrom()); if (getLogger().isDebugEnabled()) {
         * StringBuffer logBuffer = new StringBuffer(128)
         * .append(this.getClass().getName())
         * .append(" Patching To: field for message ")
         * .append(" with  From: field");
         * getLogger().debug(logBuffer.toString()); } } catch
         * (MessagingException e1) {
         * getLogger().error("Unable to set to: field to from: field", e); } }
         */
        return mimeMessage;
    }

    /**
     * Generate a hex representation of an MD5 checksum on the emailbody
     * 
     * @param emailBody
     * @return A hex representation of the text
     * @throws NoSuchAlgorithmException
     */
    private String generateKeyValue(String emailBody) throws NoSuchAlgorithmException {
        // MD5 the email body for a reilable (ha ha) key
        byte[] digArray = MessageDigest.getInstance("MD5").digest(emailBody.getBytes());
        StringBuffer digest = new StringBuffer();
        for (int i = 0; i < digArray.length; i++) {
            digest.append(Integer.toString(digArray[i], Character.MAX_RADIX).toUpperCase(Locale.US));
        }
        return digest.toString();
    }

    /**
     * Parse the mbox file.
     * 
     * @param ins
     *            The random access file to load. Note that the file may or may
     *            not start at offset 0 in the file
     * @param messAct
     *            The action to take when a message is found
     */
    private MimeMessage parseMboxFile(RandomAccessFile ins, MessageAction messAct) {
        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Start parsing ").append(mboxFile);

            getLogger().debug(logBuffer.toString());
        }
        try {

            Pattern sepMatchPattern = Pattern.compile("^From (.*) (.*):(.*):(.*)$");

            int c;
            boolean inMessage = false;
            StringBuffer messageBuffer = new StringBuffer();
            String previousMessageSeparator = null;
            boolean foundSep = false;

            long prevMessageStart = ins.getFilePointer();
            if (BUFFERING) {
                String line = null;
                while ((line = ins.readLine()) != null) {
                    foundSep = sepMatchPattern.matcher(line).matches();

                    if (foundSep && inMessage) {
                        // if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                        // getLogger().debug(this.getClass().getName() +
                        // " Invoking " + messAct.getClass() + " at " +
                        // prevMessageStart);
                        // }
                        MimeMessage endResult = messAct.messageAction(previousMessageSeparator, messageBuffer.toString(), prevMessageStart);
                        if (messAct.isComplete()) {
                            // I've got what I want so just exit
                            return endResult;
                        }
                        previousMessageSeparator = line;
                        prevMessageStart = ins.getFilePointer() - line.length();
                        messageBuffer = new StringBuffer();
                        inMessage = true;
                    }
                    // Only done at the start (first header)
                    if (foundSep && !inMessage) {
                        previousMessageSeparator = line.toString();
                        inMessage = true;
                    }
                    if (!foundSep && inMessage) {
                        messageBuffer.append(line).append("\n");
                    }
                }
            } else {
                StringBuffer line = new StringBuffer();
                while ((c = ins.read()) != -1) {
                    if (c == 10) {
                        foundSep = sepMatchPattern.matcher(line).matches();
                        if (foundSep && inMessage) {
                            // if ((DEEP_DEBUG) &&
                            // (getLogger().isDebugEnabled())) {
                            // getLogger().debug(this.getClass().getName() +
                            // " Invoking " + messAct.getClass() + " at " +
                            // prevMessageStart);
                            // }
                            MimeMessage endResult = messAct.messageAction(previousMessageSeparator, messageBuffer.toString(), prevMessageStart);
                            if (messAct.isComplete()) {
                                // I've got what I want so just exit
                                return endResult;
                            }
                            previousMessageSeparator = line.toString();
                            prevMessageStart = ins.getFilePointer() - line.length();
                            messageBuffer = new StringBuffer();
                            inMessage = true;
                        }
                        // Only done at the start (first header)
                        if (foundSep && inMessage == false) {
                            previousMessageSeparator = line.toString();
                            inMessage = true;
                        }
                        if (!foundSep) {
                            messageBuffer.append(line).append((char) c);
                        }
                        line = new StringBuffer(); // Reset buffer
                    } else {
                        line.append((char) c);
                    }
                }
            }

            if (messageBuffer.length() != 0) {
                // process last message
                return messAct.messageAction(previousMessageSeparator, messageBuffer.toString(), prevMessageStart);
            }
        } catch (IOException ioEx) {
            getLogger().error("Unable to write file (General I/O problem) " + mboxFile, ioEx);
        } catch (PatternSyntaxException e) {
            getLogger().error("Bad regex passed " + mboxFile, e);
        } finally {
            if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Finished parsing ").append(mboxFile);

                getLogger().debug(logBuffer.toString());
            }
        }
        return null;
    }

    /**
     * Find a given message<br>
     * This method will first use selectMessage(key) to see if the key/offset
     * combination allows us to skip parts of the file and only load the message
     * we are interested in
     * 
     * @param key
     *            The key of the message to find
     */
    private MimeMessage findMessage(String key) {
        MimeMessage foundMessage = null;

        // See if we can get the message by using the cache position first
        foundMessage = selectMessage(key);
        if (foundMessage == null) {
            // If the message is not found something has changed from
            // the cache. The cache may have been invalidated by
            // another method, or the file may have been replaced from
            // underneath us. Reload the cache, and try again.
            mList = null;
            loadKeys();
            foundMessage = selectMessage(key);
        }
        return foundMessage;
    }

    /**
     * Quickly find a message by using the stored message offsets
     * 
     * @param key
     *            The key of the message to find
     */
    private MimeMessage selectMessage(final String key) {
        MimeMessage foundMessage = null;
        // Can we find the key first
        if (mList == null || !mList.containsKey(key)) {
            // Not initiailised so no point looking
            if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" mList - key not found ").append(mboxFile);

                getLogger().debug(logBuffer.toString());
            }
            return foundMessage;
        }
        long messageStart = ((Long) mList.get(key)).longValue();
        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Load message starting at offset ").append(messageStart).append(" from file ").append(mboxFile);

            getLogger().debug(logBuffer.toString());
        }
        // Now try and find the position in the file
        RandomAccessFile ins = null;
        try {
            ins = new RandomAccessFile(mboxFile, "r");
            if (messageStart != 0) {
                ins.seek(messageStart - 1);
            }
            MessageAction op = new MessageAction() {
                public boolean isComplete() {
                    return true;
                }

                public MimeMessage messageAction(String messageSeparator, String bodyText, long messageStart) {
                    try {
                        if (key.equals(generateKeyValue(bodyText))) {
                            getLogger().debug(this.getClass().getName() + " Located message. Returning MIME message");
                            return convertTextToMimeMessage(bodyText);
                        }
                    } catch (NoSuchAlgorithmException e) {
                        getLogger().error("MD5 not supported! ", e);
                    }
                    return null;
                }
            };
            foundMessage = this.parseMboxFile(ins, op);
        } catch (FileNotFoundException e) {
            getLogger().error("Unable to save(open) file (File not found) " + mboxFile, e);
        } catch (IOException e) {
            getLogger().error("Unable to write file (General I/O problem) " + mboxFile, e);
        } finally {
            if (foundMessage == null) {
                if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                    StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" select - message not found ").append(mboxFile);

                    getLogger().debug(logBuffer.toString());
                }
            }
            if (ins != null)
                try {
                    ins.close();
                } catch (IOException e) {
                    getLogger().error("Unable to close file (General I/O problem) " + mboxFile, e);
                }
        }
        return foundMessage;
    }

    /**
     * Load the message keys and file pointer offsets from disk
     */
    private synchronized void loadKeys() {
        if (mList != null) {
            return;
        }
        RandomAccessFile ins = null;
        try {
            ins = new RandomAccessFile(mboxFile, "r");
            long initialCapacity = (ins.length() > MLISTPRESIZEFACTOR ? ins.length() / MLISTPRESIZEFACTOR : 0);
            if (initialCapacity < DEFAULTMLISTCAPACITY) {
                initialCapacity = DEFAULTMLISTCAPACITY;
            }
            if (initialCapacity > Integer.MAX_VALUE) {
                initialCapacity = Integer.MAX_VALUE - 1;
            }
            this.mList = new Hashtable<String, Long>((int) initialCapacity);
            this.parseMboxFile(ins, new MessageAction() {
                public boolean isComplete() {
                    return false;
                }

                public MimeMessage messageAction(String messageSeparator, String bodyText, long messageStart) {
                    try {
                        String key = generateKeyValue(bodyText);
                        mList.put(key, Long.valueOf(messageStart));
                        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                            getLogger().debug(this.getClass().getName() + " Key " + key + " at " + messageStart);
                        }

                    } catch (NoSuchAlgorithmException e) {
                        getLogger().error("MD5 not supported! ", e);
                    }
                    return null;
                }
            });
            // System.out.println("Done Load keys!");
        } catch (FileNotFoundException e) {
            getLogger().error("Unable to save(open) file (File not found) " + mboxFile, e);
            this.mList = new Hashtable<String, Long>((int) DEFAULTMLISTCAPACITY);
        } catch (IOException e) {
            getLogger().error("Unable to write file (General I/O problem) " + mboxFile, e);
        } finally {
            if (ins != null)
                try {
                    ins.close();
                } catch (IOException e) {
                    getLogger().error("Unable to close file (General I/O problem) " + mboxFile, e);
                }
        }
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#store(Mail)
     */
    public void store(Mail mc) {

        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Will store message to file ").append(mboxFile);

            getLogger().debug(logBuffer.toString());
        }
        this.mList = null;
        // Now make up the from header
        String fromHeader = null;
        String message = null;
        try {
            message = getRawMessage(mc.getMessage());
            // check for nullsender
            if (mc.getMessage().getFrom() == null) {
                fromHeader = "From   " + dy.format(Calendar.getInstance().getTime());
            } else {
                fromHeader = "From " + mc.getMessage().getFrom()[0] + " " + dy.format(Calendar.getInstance().getTime());
            }

        } catch (IOException e) {
            getLogger().error("Unable to parse mime message for " + mboxFile, e);
        } catch (MessagingException e) {
            getLogger().error("Unable to parse mime message for " + mboxFile, e);
        }
        // And save only the new stuff to disk
        RandomAccessFile saveFile = null;
        try {
            saveFile = new RandomAccessFile(mboxFile, "rw");
            saveFile.seek(saveFile.length()); // Move to the end
            saveFile.writeBytes((fromHeader + "\n"));
            saveFile.writeBytes((message + "\n"));
            saveFile.close();

        } catch (FileNotFoundException e) {
            getLogger().error("Unable to save(open) file (File not found) " + mboxFile, e);
        } catch (IOException e) {
            getLogger().error("Unable to write file (General I/O problem) " + mboxFile, e);
        }
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#list()
     */
    public Iterator<String> list() {
        loadKeys();
        ArrayList<String> keys = new ArrayList<String>(mList.keySet());

        if (keys.isEmpty() == false) {
            // find the first message. This is a trick to make sure that if
            // the file is changed out from under us, we will detect it and
            // correct for it BEFORE we return the iterator.
            findMessage((String) keys.iterator().next());
        }
        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" ").append(keys.size()).append(" keys to be iterated over.");

            getLogger().debug(logBuffer.toString());
        }
        if (fifo)
            Collections.sort(keys); // Keys is a HashSet; impose FIFO for apps
                                    // that need it
        return keys.iterator();
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#retrieve(String)
     */
    public Mail retrieve(String key) {

        loadKeys();
        MailImpl res = null;

        MimeMessage foundMessage = findMessage(key);
        if (foundMessage == null) {
            getLogger().error("found message is null!");
            return null;
        }
        res = new MailImpl();
        res.setMessage(foundMessage);
        res.setName(key);
        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Retrieving entry for key ").append(key);

            getLogger().debug(logBuffer.toString());
        }
        return res;
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#remove(Mail)
     */
    public void remove(Mail mail) {
        ArrayList<Mail> remArray = new ArrayList<Mail>();
        remArray.add(mail);
        remove(remArray);
    }

    /**
     * Attempt to get a lock on the mbox by creating the file mboxname.lock
     * 
     * @throws Exception
     */
    private void lockMBox() throws Exception {
        // Create the lock file (if possible)
        String lockFileName = mboxFile + LOCKEXT;
        int sleepCount = 0;
        File mBoxLock = new File(lockFileName);
        if (!mBoxLock.createNewFile()) {
            // This is not good, somebody got the lock before me
            // So wait for a file
            while (!mBoxLock.createNewFile() && sleepCount < MAXSLEEPTIMES) {
                try {
                    if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
                        StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Waiting for lock on file ").append(mboxFile);

                        getLogger().debug(logBuffer.toString());
                    }

                    Thread.sleep(LOCKSLEEPDELAY);
                    sleepCount++;
                } catch (InterruptedException e) {
                    getLogger().error("File lock wait for " + mboxFile + " interrupted!", e);

                }
            }
            if (sleepCount >= MAXSLEEPTIMES) {
                throw new Exception("Unable to get lock on file " + mboxFile);
            }
        }
    }

    /**
     * Unlock a previously locked mbox file
     */
    private void unlockMBox() {
        // Just delete the MBOX file
        String lockFileName = mboxFile + LOCKEXT;
        File mBoxLock = new File(lockFileName);
        if (!mBoxLock.delete()) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Failed to delete lock file ").append(lockFileName);
            getLogger().error(logBuffer.toString());
        }
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#remove(Collection)
     */
    public void remove(final Collection<Mail> mails) {
        if ((DEEP_DEBUG) && (getLogger().isDebugEnabled())) {
            StringBuffer logBuffer = new StringBuffer(128).append(this.getClass().getName()).append(" Removing entry for key ").append(mails);

            getLogger().debug(logBuffer.toString());
        }
        // The plan is as follows:
        // Attempt to locate the message in the file
        // by reading through the
        // once we've done that then seek to the file
        try {
            RandomAccessFile ins = new RandomAccessFile(mboxFile, "r"); // The
                                                                        // source
            final RandomAccessFile outputFile = new RandomAccessFile(mboxFile + WORKEXT, "rw"); // The
                                                                                                // destination
            parseMboxFile(ins, new MessageAction() {
                public boolean isComplete() {
                    return false;
                }

                public MimeMessage messageAction(String messageSeparator, String bodyText, long messageStart) {
                    // Write out the messages as we go, until we reach the key
                    // we want
                    try {
                        String currentKey = generateKeyValue(bodyText);
                        boolean foundKey = false;
                        Iterator<Mail> mailList = mails.iterator();
                        String key;
                        while (mailList.hasNext()) {
                            // Attempt to find the current key in the array
                            key = mailList.next().getName();
                            if (key.equals(currentKey)) {
                                // Don't write the message to disk
                                foundKey = true;
                                break;
                            }
                        }
                        if (foundKey == false) {
                            // We didn't find the key in the array so we will
                            // keep it
                            outputFile.writeBytes(messageSeparator + "\n");
                            outputFile.writeBytes(bodyText);

                        }
                    } catch (NoSuchAlgorithmException e) {
                        getLogger().error("MD5 not supported! ", e);
                    } catch (IOException e) {
                        getLogger().error("Unable to write file (General I/O problem) " + mboxFile, e);
                    }
                    return null;
                }
            });
            ins.close();
            outputFile.close();
            // Delete the old mbox file
            File mbox = new File(mboxFile);
            if (!mbox.delete()) {
                throw new IOException("Unable to delete file " + mbox);
            }
            // And rename the lock file to be the new mbox
            mbox = new File(mboxFile + WORKEXT);
            if (!mbox.renameTo(new File(mboxFile))) {
                throw new IOException("Failed to rename file " + mbox + " -> " + mboxFile);
            }

            // Now delete the keys in mails from the main hash
            Iterator<Mail> mailList = mails.iterator();
            String key;
            while (mailList.hasNext()) {
                // Attempt to find the current key in the array
                key = mailList.next().getName();
                mList.remove(key);
            }

        } catch (FileNotFoundException e) {
            getLogger().error("Unable to save(open) file (File not found) " + mboxFile, e);
        } catch (IOException e) {
            getLogger().error("Unable to write file (General I/O problem) " + mboxFile, e);
        }
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#remove(String)
     */
    public void remove(String key) {
        loadKeys();
        try {
            lockMBox();
        } catch (Exception e) {
            getLogger().error("Lock failed!", e);
            return; // No lock, so exit
        }
        ArrayList<Mail> keys = new ArrayList<Mail>();
        keys.add(retrieve(key));

        this.remove(keys);
        unlockMBox();
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#lock(String)
     */
    public boolean lock(String key) {
        return false;
    }

    /**
     * @see org.apache.james.mailrepository.api.MailRepository#unlock(String)
     */
    public boolean unlock(String key) {
        return false;
    }
}
