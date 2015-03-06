package com.braunster.androidchatsdk.firebaseplugin.firebase;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.braunster.androidchatsdk.firebaseplugin.firebase.listeners.IncomingMessagesListener;
import com.braunster.androidchatsdk.firebaseplugin.firebase.listeners.ThreadDetailsChangeListener;
import com.braunster.androidchatsdk.firebaseplugin.firebase.listeners.UserAddedToThreadListener;
import com.braunster.androidchatsdk.firebaseplugin.firebase.listeners.UserDetailsChangeListener;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.dao.BFollower;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BThreadDao;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.interfaces.AppEvents;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BFirebaseDefines;
import com.braunster.chatsdk.network.BNetworkManager;
import com.braunster.chatsdk.network.BPath;
import com.braunster.chatsdk.network.events.AbstractEventManager;
import com.braunster.chatsdk.network.events.BatchedEvent;
import com.braunster.chatsdk.network.events.Event;
import com.firebase.client.ChildEventListener;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.Query;
import com.firebase.client.ValueEventListener;

import org.apache.commons.lang3.StringUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by braunster on 30/06/14.
 */

public class EventManager extends AbstractEventManager implements AppEvents {

    private static final String TAG = EventManager.class.getSimpleName();
    private static final boolean DEBUG = Debug.EventManager;

    public static final String THREAD_ID = "threadID";
    public static final String USER_ID = "userID";


    private static EventManager instance;

    private static final String MSG_PREFIX = "msg_";
    private static final String USER_PREFIX = "user_";

    private ConcurrentHashMap<String, Event> events = new ConcurrentHashMap<String, Event>();

    private List<String> threadsIds = new ArrayList<String>();
    private List<String> handledAddedUsersToThreadIDs = new ArrayList<String>();
    private List<String> handledMessagesThreadsID = new ArrayList<String>();
    private List<String> usersIds = new ArrayList<String>();
    private List<String> handleFollowDataChangeUsersId = new ArrayList<String>();

    public ConcurrentHashMap<String, FirebaseEventCombo> listenerAndRefs = new ConcurrentHashMap<String, FirebaseEventCombo>();

    public static EventManager getInstance(){
        if (instance == null)
            instance = new EventManager();

        return instance;
    }

    private final EventHandler handlerThread = new EventHandler(this);
    private final EventHandler handlerMessages = new EventHandler(this);
    private final EventHandler handlerUserDetails = new EventHandler(this);
    private final EventHandler handlerUserAdded = new EventHandler(this);

    private EventManager(){
        threadsIds = Collections.synchronizedList(threadsIds);
        handledAddedUsersToThreadIDs = Collections.synchronizedList(handledAddedUsersToThreadIDs);;
        handledMessagesThreadsID = Collections.synchronizedList(handledMessagesThreadsID);
        usersIds = Collections.synchronizedList(usersIds);
    }

    static class EventHandler extends Handler{
        WeakReference<EventManager> manager;

        public EventHandler(EventManager manager){
            super(Looper.getMainLooper());
            this.manager = new WeakReference<EventManager>(manager);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            switch (msg.what)
            {
                case AppEvents.USER_DETAILS_CHANGED:
                    if (notNull())
                        manager.get().onUserDetailsChange((BUser) msg.obj);
                    break;

                case AppEvents.MESSAGE_RECEIVED:
                    if (notNull())
                        manager.get().onMessageReceived((BMessage) msg.obj);
                    break;

                case AppEvents.THREAD_DETAILS_CHANGED:
                    if (notNull())
                        manager.get().onThreadDetailsChanged((String) msg.obj);
                    break;

                case AppEvents.USER_ADDED_TO_THREAD:
                    if (notNull())
                        manager.get().onUserAddedToThread(msg.getData().getString(THREAD_ID), msg.getData().getString(USER_ID));
                    break;

                case AppEvents.FOLLOWER_ADDED:
                    if (notNull())
                        manager.get().onFollowerAdded((BFollower) msg.obj);
                    break;

                case AppEvents.FOLLOWER_REMOVED:
                    if (notNull())
                        manager.get().onFollowerRemoved();
                    break;

                case AppEvents.USER_TO_FOLLOW_ADDED:
                    if (notNull())
                        manager.get().onUserToFollowAdded((BFollower) msg.obj);
                    break;

                case AppEvents.USER_TO_FOLLOW_REMOVED:
                    if (notNull())
                        manager.get().onUserToFollowRemoved();
                    break;
            }
        }

        private boolean notNull(){
            return manager.get() != null;
        }
    }

    @Override
    public boolean onUserAddedToThread(String threadId, final String userId) {
        if (DEBUG) Log.i(TAG, "onUserAddedToThread");
        post(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                handleUsersDetailsChange(userId);
            }
        });


        for (Event e : events.values())
        {
            if (e == null)
                continue;

            if (StringUtils.isNotEmpty(e.getEntityId())  && StringUtils.isNotEmpty(threadId)
                    &&  !e.getEntityId().equals(threadId) )
                continue;

            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.ThreadEvent, threadId);

            e.onUserAddedToThread(threadId, userId);
        }

        return false;
    }

    @Override
    public boolean onFollowerAdded(final BFollower follower) {
        post(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                handleUsersDetailsChange(follower.getUser().getEntityID());
            }
        });

        if (follower!=null)
            for (Event  e : events.values())
            {
                if (e == null)
                    continue;

                if(e instanceof BatchedEvent)
                    ((BatchedEvent) e).add(Event.Type.FollwerEvent, follower.getUser().getEntityID());

                e.onFollowerAdded(follower);
            }
        return false;
    }

    @Override
    public boolean onFollowerRemoved() {
        for ( Event  e : events.values())
        {
            if (e == null )
                continue;

            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.FollwerEvent);

            e.onFollowerRemoved();
        }
        return false;
    }

    @Override
    public boolean onUserToFollowAdded(final BFollower follower) {
        post(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                handleUsersDetailsChange(follower.getUser().getEntityID());
            }
        });

        if (follower!=null)
            for (Event e : events.values())
            {
                if (e == null)
                    continue;

                if(e instanceof BatchedEvent)
                    ((BatchedEvent) e).add(Event.Type.FollwerEvent, follower.getUser().getEntityID());

                e.onUserToFollowAdded(follower);
            }
        return false;
    }

    @Override
    public boolean onUserToFollowRemoved() {
        for (Event  e : events.values())
        {
            if (e == null )
                continue;

            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.FollwerEvent);

            e.onUserToFollowRemoved();
        }
        return false;
    }

    @Override
    public boolean onUserDetailsChange(BUser user) {
        if (DEBUG) Log.i(TAG, "onUserDetailsChange");
        if (user == null)
            return false;

        for ( Event e : events.values())
        {
            if (e == null)
                continue;

            // We check to see if the listener specified a specific user that he wants to listen to.
            // If we could find and match the data we ignore it.
            if (StringUtils.isNotEmpty(e.getEntityId())  && StringUtils.isNotEmpty(user.getEntityID())
                    &&  !e.getEntityId().equals(user.getEntityID()) )
                continue;


            if(e instanceof BatchedEvent)
                ((BatchedEvent) e ).add(Event.Type.UserDetailsEvent, user.getEntityID());

            e.onUserDetailsChange(user);
        }

        return false;
    }

    @Override
    public boolean onMessageReceived(BMessage message) {
        if (DEBUG) Log.i(TAG, "onMessageReceived");
        for (Event e : events.values())
        {
            if (e == null)
                continue;

            // We check to see if the listener specified a specific thread that he wants to listen to.
            // If we could find and match the data we ignore it.
            if (StringUtils.isNotEmpty(e.getEntityId()) && message.getBThreadOwner() != null
                    && message.getBThreadOwner().getEntityID() != null
                    && !message.getBThreadOwner().getEntityID().equals(e.getEntityId()))
                    continue;


            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.MessageEvent, message.getEntityID());

            e.onMessageReceived(message);
        }

        return false;
    }

    @Override
    public boolean onThreadDetailsChanged(final String threadId) {
        if (DEBUG) Log.i(TAG, "onThreadDetailsChanged");
        post(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                // Also listen to the thread users
                // This will allow us to update the users in the database
                handleUsersAddedToThread(threadId);
//               Handle incoming messages
                handleMessages(threadId);
            }
        });

        for (Event e : events.values())
        {
            if (e  == null)
                continue;

            
            if (StringUtils.isNotEmpty(e.getEntityId()) && !threadId.equals(e.getEntityId()))
                continue;

            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.ThreadEvent, threadId);

            e.onThreadDetailsChanged(threadId);
        }

        // TODO add option to listen to specific thread and from specific type.

        return false;
    }

    @Override
    public boolean onThreadIsAdded(String threadId) {
        if (DEBUG) Log.i(TAG, "onThreadIsAdded");
        for (Event e : events.values())
        {

            if (e == null)
                continue;
            
            if (StringUtils.isNotEmpty(e.getEntityId()) && !threadId.equals(e.getEntityId()))
                continue;

            if(e instanceof BatchedEvent)
                ((BatchedEvent) e).add(Event.Type.ThreadEvent, threadId);

            e.onThreadIsAdded(threadId);
        }

        return false;
    }


    /*##########################################################################################*/
    /*------Assigning listeners to Firebase refs. ------*/
    /**Set listener to thread details change.*/
    public void handleThreadDetails(final String threadId){

        final FirebasePaths threadRef = FirebasePaths.threadRef(threadId);

        // Add an observer to the thread details so we get
        // updated when the thread details change
        FirebasePaths detailsRef = threadRef.appendPathComponent(BFirebaseDefines.Path.BDetailsPath);

        FirebaseEventCombo combo = getCombo(threadId, detailsRef.toString(), new ThreadDetailsChangeListener(threadId, handlerThread));

        detailsRef.addValueEventListener(combo.getListener());
    }

    /** Set listener to users that are added to thread.*/
    public void handleUsersAddedToThread(final String threadId){
        // Check if handled.
        if (handledAddedUsersToThreadIDs.contains(threadId))
            return;

        handledAddedUsersToThreadIDs.add(threadId);

        // Also listen to the thread users
        // This will allow us to update the users in the database
        Firebase threadUsers = FirebasePaths.threadRef(threadId).child(BFirebaseDefines.Path.BUsersPath);

        UserAddedToThreadListener userAddedToThreadListener= UserAddedToThreadListener.getNewInstance(observedUserEntityID, threadId, handlerUserAdded);

        FirebaseEventCombo combo = getCombo(USER_PREFIX + threadId, threadUsers.toString(), userAddedToThreadListener);

        threadUsers.addChildEventListener(combo.getListener());
    }

    /** Handle user details change.*/
    public void handleUsersDetailsChange(String userID){
        if (DEBUG) Log.v(TAG, "handleUsersDetailsChange, Entered. " + userID);

        if (userID.equals(getCurrentUserId()))
        {
            if (DEBUG) Log.v(TAG, "handleUsersDetailsChange, Current User." + userID);
            return;
        }

        if (usersIds.contains(userID))
        {
            if (DEBUG) Log.v(TAG, "handleUsersDetailsChange, Listening." + userID);
            return;
        }

        usersIds.add(userID);

        final FirebasePaths userRef = FirebasePaths.userRef(userID);

        if (DEBUG) Log.v(TAG, "handleUsersDetailsChange, User Ref." + userRef.getRef().toString());

        UserDetailsChangeListener userDetailsChangeListener = new UserDetailsChangeListener(userID, handlerUserDetails);

        FirebaseEventCombo combo = getCombo(USER_PREFIX + userID, userRef.toString(), userDetailsChangeListener);

        userRef.addValueEventListener(combo.getListener());
    }

    @Override
    public void handleUserFollowDataChange(String userID){
        if (DEBUG) Log.v(TAG, "handleUserFollowDataChange, Entered. " + userID);


        if (handleFollowDataChangeUsersId.contains(userID))
        {
            if (DEBUG) Log.v(TAG, "handleUserFollowDataChange, Listening." + userID);
            return;
        }

        handleFollowDataChangeUsersId.add(userID);

        final FirebasePaths userRef = FirebasePaths.userRef(userID);


    }

    /** Handle incoming messages for thread.*/
    @Override
    public void handleMessages(String threadId){
        // Check if handled.
        if (handledMessagesThreadsID.contains(threadId))
            return;

        handledMessagesThreadsID.add(threadId);

        final FirebasePaths threadRef = FirebasePaths.threadRef(threadId);
        Query messagesQuery = threadRef.appendPathComponent(BFirebaseDefines.Path.BMessagesPath);

        final BThread thread = DaoCore.fetchOrCreateEntityWithEntityID(BThread.class, threadId);

        final List<BMessage> messages = thread.getMessagesWithOrder(DaoCore.ORDER_DESC);

        final IncomingMessagesListener incomingMessagesListener = new IncomingMessagesListener(handlerMessages);

        /**
         * If the thread was deleted or has no message we first check for his deletion date.
         * If has deletion date we listen to message from this day on, Else we will get the last messages.
         *
         *
         * Limiting the messages here can cause a problems,
         * If we reach the limit with new messages the new one wont be triggered and he user wont see them.
         * (He would see his if he kill the chat because they are saved locally).
         *
         * */
        if (thread.isDeleted() || messages.size() == 0)
        {
            if (DEBUG) Log.v(TAG, "Thread is Deleted");
            Firebase threadUsersPath = FirebasePaths.threadRef(threadId).appendPathComponent(BFirebaseDefines.Path.BUsersPath).appendPathComponent(getCurrentUserId())
                    .appendPathComponent(BDefines.Keys.BDeleted);

            threadUsersPath.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Query query = threadRef.appendPathComponent(BFirebaseDefines.Path.BMessagesPath);

                    // Not deleted
                    if (snapshot==null || snapshot.getValue() == null)
                    {
                        query = query.limitToLast(BDefines.MAX_MESSAGES_TO_PULL);
                    }
                    // Deleted.
                    else
                    {
                        if (DEBUG) Log.d(TAG, "Thread Deleted Value: " + snapshot.getValue());

                        // The plus 1 is needed so we wont receive the last message again.
                        query = query.startAt(((Long) snapshot.getValue()));
                    }

                    FirebaseEventCombo combo = getCombo(MSG_PREFIX + thread.getEntityID(), query.getRef().toString(), incomingMessagesListener);

                    query.addChildEventListener(combo.getListener());
                }

                @Override
                public void onCancelled(FirebaseError firebaseError) {

                }
            });

            return;
        }
        else if (messages.size() > 0)
        {
            // The plus 1 is needed so we wont receive the last message again.
            messagesQuery = messagesQuery.startAt(messages.get(0).getDate().getTime() + 1);

            // Set any message that received as new.
            incomingMessagesListener.setNew(true);
        }
        else
        {
            messagesQuery = messagesQuery.limitToLast(BDefines.MAX_MESSAGES_TO_PULL);
        }

        FirebaseEventCombo combo = getCombo(MSG_PREFIX + thread.getEntityID(), messagesQuery.getRef().toString(), incomingMessagesListener);

        messagesQuery.addChildEventListener(combo.getListener());
    }

    /** Hnadle the thread by given id, If thread is not handled already a listener
     * to thread details change will be assigned. After details received the messages and added users listeners will be assign.*/
    @Override
     public void handleThread(final String threadID){

        if (threadID == null)
        {
            if (DEBUG) Log.e(TAG, "observeThread, ThreadId is null, ID: " + threadID);
            return;
        }

        if (DEBUG) Log.v(TAG, "observeThread, ThreadID: " + threadID);


        if (!isListeningToThread(threadID))
        {
            threadsIds.add(threadID);

            final FirebasePaths threadRef = FirebasePaths.threadRef(threadID);


            // Add an observer to the thread details so we get
            // updated when the thread details change
            // When a thread details change a listener for added users is assign to the thread(If not assigned already).
            // For each added user a listener will be assign for his details change.
            handleThreadDetails(threadID);
        }
        else if (DEBUG) Log.e(TAG, "Thread is already handled..");
    }

    private String observedUserEntityID = "";
    @Override
    public void observeUser(final BUser user){

        observedUserEntityID = user.getEntityID();

        FirebasePaths.userRef(observedUserEntityID)
                .appendPathComponent(BFirebaseDefines.Path.BThreadPath)
                .addChildEventListener(threadAddedListener);

        FirebasePaths.userRef(observedUserEntityID).appendPathComponent(BFirebaseDefines.Path.BFollowers).addChildEventListener(followerEventListener);
        FirebasePaths.userRef(observedUserEntityID).appendPathComponent(BFirebaseDefines.Path.BFollows).addChildEventListener(followsEventListener);

        FirebasePaths.publicThreadsRef().addChildEventListener(threadAddedListener);

        post(new Runnable() {
            @Override
            public void run() {
                for (BUser contact : user.getContacts())
                    handleUsersDetailsChange(contact.getEntityID());
            }
        });

    }

    private ChildEventListener threadAddedListener = new ChildEventListener() {
        @Override
        public void onChildAdded(final DataSnapshot snapshot, String s) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) Log.i(TAG, "Thread is added. SnapShot Ref: " + snapshot.getRef().toString());
                    /*android.os.Process.getThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);*/

                    String threadFirebaseID;
                    BPath path = BPath.pathWithPath(snapshot.getRef().toString());
                    if (path.isEqualToComponent(BFirebaseDefines.Path.BPublicThreadPath))
                        threadFirebaseID = path.idForIndex(0);
                    else threadFirebaseID = path.idForIndex(1);

                    if (DEBUG) Log.i(TAG, "Thread is added, Thread EntityID: " + threadFirebaseID);

                    if (!isListeningToThread(threadFirebaseID))
                    {
                        // Load the thread from firebase only if he is not exist.
                        // There is no reason to load if exist because the event manager will collect all the thread data.
                        if (threadFirebaseID != null && DaoCore.fetchEntityWithProperty(BThread.class, BThreadDao.Properties.EntityID, threadFirebaseID) == null)
                            BFirebaseInterface.objectFromSnapshot(snapshot);

                        handleThread(threadFirebaseID);

                        onThreadIsAdded(threadFirebaseID);
                    }
                }
            });
        }

        //region Not used.
        @Override
        public void onChildChanged(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onChildRemoved(DataSnapshot snapshot) {

        }

        @Override
        public void onChildMoved(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onCancelled(FirebaseError error) {

        }
        //endregion
    };

    private ChildEventListener followerEventListener = new ChildEventListener() {
        @Override
        public void onChildAdded(final DataSnapshot snapshot, String s) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) Log.i(TAG, "Follower is added. SnapShot Ref: " + snapshot.getRef().toString());
                    BFollower follower = (BFollower) BFirebaseInterface.objectFromSnapshot(snapshot);

                    onFollowerAdded(follower);
                    handleUsersDetailsChange(follower.getUser().getEntityID());
                }
            });
        }

        @Override
        public void onChildChanged(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onChildRemoved(DataSnapshot snapshot) {
            if (DEBUG) Log.i(TAG, "Follower is removed. SnapShot Ref: " + snapshot.getRef().toString());
            BFollower follower = (BFollower) BFirebaseInterface.objectFromSnapshot(snapshot);
            DaoCore.deleteEntity(follower);
            onFollowerRemoved();
        }

        @Override
        public void onChildMoved(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onCancelled(FirebaseError firebaseError) {

        }
    };

    private ChildEventListener followsEventListener = new ChildEventListener() {
        @Override
        public void onChildAdded(final DataSnapshot snapshot, String s) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) Log.i(TAG, "Follower is added. SnapShot Ref: " + snapshot.getRef().toString());
                    BFollower follower = (BFollower) BFirebaseInterface.objectFromSnapshot(snapshot);

                    onUserToFollowAdded(follower);
                    handleUsersDetailsChange(follower.getUser().getEntityID());
                }
            });
        }

        @Override
        public void onChildChanged(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onChildRemoved(DataSnapshot snapshot) {
            if (DEBUG) Log.i(TAG, "Follower is removed. SnapShot Ref: " + snapshot.getRef().toString());
            BFollower follower = (BFollower) BFirebaseInterface.objectFromSnapshot(snapshot);
            if (DEBUG) Log.i(TAG, "Follower is removed. UserID: " + follower.getUser().getEntityID() + ", OwnerID: " + follower.getOwner().getEntityID());
            DaoCore.deleteEntity(follower);
            onUserToFollowRemoved();
        }

        @Override
        public void onChildMoved(DataSnapshot snapshot, String s) {

        }

        @Override
        public void onCancelled(FirebaseError firebaseError) {

        }
    };

    /** Check to see if the given thread id is already handled by this class.
     * @return true if handled.*/
    @Override
     public boolean isListeningToThread(String entityID){
        return threadsIds.contains(entityID);
    }

    /** Remove listeners from thread id. The listener's are The thread details, messages and added users.*/
    public void stopListeningToThread(String threadID){
        if (DEBUG) Log.v(TAG, "stopListeningToThread, ThreadID: "  + threadID);

        if (listenerAndRefs.containsKey(threadID) && listenerAndRefs.get(threadID) != null)
            listenerAndRefs.get(threadID).breakCombo();

        if (listenerAndRefs.containsKey(MSG_PREFIX + threadID) && listenerAndRefs.get(MSG_PREFIX  + threadID) != null)
            listenerAndRefs.get(MSG_PREFIX  + threadID).breakCombo();

        if (listenerAndRefs.containsKey(USER_PREFIX + threadID) && listenerAndRefs.get(USER_PREFIX + threadID) != null)
            listenerAndRefs.get(USER_PREFIX  + threadID).breakCombo();

        // Removing the combo's from the Map.
        listenerAndRefs.remove(threadID);
        listenerAndRefs.remove(MSG_PREFIX  + threadID);
        listenerAndRefs.remove(USER_PREFIX  + threadID);

        threadsIds.remove(threadID);
        handledMessagesThreadsID.remove(threadID);
        handledAddedUsersToThreadIDs.remove(threadID);
    }

    /** Get a combo object for given index, ref and listener.
     *  The combo is used to keep the firebase ref path and their listeners so we can remove the listener when needed.*/
    private FirebaseEventCombo getCombo(String index, String ref, FirebaseGeneralEvent listener){
        FirebaseEventCombo combo = FirebaseEventCombo.getNewInstance(listener, ref);
        saveCombo(index, combo);
        return combo;
    }

    /** Save the combo to the combo map.*/
    private void saveCombo(String index, FirebaseEventCombo combo){
        listenerAndRefs.put(index, combo);
    }

    
    
    /*##########################################################################################*/
    /*------Clearing all the data from class. ------*/

    /*------Assigning app events. ------*/
    @Override
    public void addEvent(Event appEvents){
        Event e = events.put(appEvents.getTag(), appEvents);
        
        if (e != null )
            e.kill();
    }

    /** Removes an app event by tag.*/
    @Override
    public boolean removeEventByTag(String tag){

        if (DEBUG) Log.v(TAG, "removeEventByTag, Tag: " + tag);

        if (StringUtils.isEmpty(tag)){
            return false;
        }

        Event e = events.remove(tag);
        
        if (e != null)
        {
            if (DEBUG) Log.i(TAG, "killing event, Tag: " + e.getTag());
            e.kill();
        }

        if (DEBUG && e == null) Log.d(TAG, "Event was not found.");
        
        return e != null;
    }

    /** Check if there is a AppEvent listener with the currnt tag, Could be AppEvent or one of his child(MessageEventListener, ThreadEventListener, UserEventListener).
     * @return true if found.*/
    @Override
    public boolean isEventTagExist(String tag){
        return events.containsKey(tag);
    }
    
    /** Remove all firebase listeners and all app events listeners. After removing all class list will be cleared.*/
    public void removeAll(){

        FirebasePaths.userRef(observedUserEntityID)
                .appendPathComponent(BFirebaseDefines.Path.BThreadPath)
                .removeEventListener(threadAddedListener);

        FirebasePaths.userRef(observedUserEntityID).appendPathComponent(BFirebaseDefines.Path.BFollowers).removeEventListener(followerEventListener);
        FirebasePaths.userRef(observedUserEntityID).appendPathComponent(BFirebaseDefines.Path.BFollows).removeEventListener(followsEventListener);

        observedUserEntityID = "";

        FirebasePaths.publicThreadsRef().removeEventListener(threadAddedListener);

        Set<String> Keys = listenerAndRefs.keySet();

        FirebaseEventCombo combo;

        Iterator<String> iter = Keys.iterator();
        String key;
        while (iter.hasNext())
        {
            key = iter.next();
            if (DEBUG) Log.d(TAG, "Removing listener, Key: " + key);

            combo = listenerAndRefs.get(key);
            
            if (combo != null)
                combo.breakCombo();
        }

        Executor.getInstance().restart();

        clearLists();
    }

    /** Clearing all the lists.*/
    private void clearLists(){
        listenerAndRefs.clear();

        // Killing all events
        for (Event e : events.values())
        {
            if (e != null)
                e.kill();
        }
        
        events.clear();

        threadsIds.clear();
        usersIds.clear();
        handledMessagesThreadsID.clear();
        handledAddedUsersToThreadIDs.clear();
        handleFollowDataChangeUsersId.clear();
    }

    
    
    /*##########################################################################################*/

    /** get the current user entity so we know not to listen to his details and so on.*/
    public static String getCurrentUserId() {
        return BNetworkManager.sharedManager().getNetworkAdapter().currentUser().getEntityID();
    }

    /** Print save data of this class. Id's List and listener and refs. Used for debugging.*/
    public void printDataReport(){
        for (String s : threadsIds)
            Log.i(TAG, "Listening to thread ID: "  + s);

        for (String u: usersIds)
            Log.i(TAG, "handled users details, user ID: "  + u);

 /*       for (Event e : messageEventList)
            Log.i(TAG, "Msg Event, Tag: " + e.getTag());*/

        for (String s : handledAddedUsersToThreadIDs)
            Log.i(TAG, "handled added users, Thread ID: " + s);

        for (String s : handledMessagesThreadsID)
            Log.i(TAG, "handled messages, Thread ID: " + s);
    }

    private void post(Runnable runnable){
        Executor.getInstance().execute(runnable);
    }

    public static class Executor {
        // Sets the amount of time an idle thread waits before terminating
        private static final int KEEP_ALIVE_TIME = 20;
        // Sets the Time Unit to seconds
        private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

        private LinkedBlockingQueue<Runnable>  workQueue = new LinkedBlockingQueue<Runnable>();
        /*
         * Gets the number of available cores
         * (not always the same as the maximum number of cores)
         */
        private static int NUMBER_OF_CORES =
                Runtime.getRuntime().availableProcessors();

        private static int MAX_THREADS = 15;

        private ThreadPoolExecutor threadPool;

        private static Executor instance;

        public static Executor getInstance() {
            if (instance == null)
                instance = new Executor();
            return instance;
        }

        private Executor(){
            // Creates a thread pool manager
            threadPool = new ThreadPoolExecutor(
                    NUMBER_OF_CORES,       // Initial pool size
                    MAX_THREADS,       // Max pool size
                    KEEP_ALIVE_TIME,
                    KEEP_ALIVE_TIME_UNIT,
                    workQueue);
        }

        public void execute(Runnable runnable){
            threadPool.execute(runnable);
        }

        private void restart(){
            threadPool.shutdownNow();
            instance = new Executor();
        }
    }

    public static class MessageExecutor {
        // Sets the amount of time an idle thread waits before terminating
        private static final int KEEP_ALIVE_TIME = 20;
        // Sets the Time Unit to seconds
        private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

        private LinkedBlockingQueue<Runnable>  workQueue = new LinkedBlockingQueue<Runnable>();
        /*
         * Gets the number of available cores
         * (not always the same as the maximum number of cores)
         */
        private static int NUMBER_OF_CORES =
                Runtime.getRuntime().availableProcessors();

        private static int MAX_THREADS = 15;

        private ThreadPoolExecutor threadPool;

        private static Executor instance;

        public static Executor getInstance() {
            if (instance == null)
                instance = new Executor();
            return instance;
        }

        private MessageExecutor(){
            // Creates a thread pool manager
            threadPool = new ThreadPoolExecutor(
                    NUMBER_OF_CORES,       // Initial pool size
                    MAX_THREADS,       // Max pool size
                    KEEP_ALIVE_TIME,
                    KEEP_ALIVE_TIME_UNIT,
                    workQueue);
        }

        public void execute(Runnable runnable){
            threadPool.execute(runnable);
        }

        private void restart(){
            threadPool.shutdownNow();
            instance = new Executor();
        }
    }

    public static class UserDetailsExecutor {
        // Sets the amount of time an idle thread waits before terminating
        private static final int KEEP_ALIVE_TIME = 20;
        // Sets the Time Unit to seconds
        private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

        private LinkedBlockingQueue<Runnable>  workQueue = new LinkedBlockingQueue<Runnable>();
        /*
         * Gets the number of available cores
         * (not always the same as the maximum number of cores)
         */
        private static int NUMBER_OF_CORES =
                Runtime.getRuntime().availableProcessors();

        private static int MAX_THREADS = 15;

        private ThreadPoolExecutor threadPool;

        private static Executor instance;

        public static Executor getInstance() {
            if (instance == null)
                instance = new Executor();
            return instance;
        }

        private UserDetailsExecutor(){
            // Creates a thread pool manager
            threadPool = new ThreadPoolExecutor(
                    NUMBER_OF_CORES,       // Initial pool size
                    MAX_THREADS,       // Max pool size
                    KEEP_ALIVE_TIME,
                    KEEP_ALIVE_TIME_UNIT,
                    workQueue);
        }

        public void execute(Runnable runnable){
            threadPool.execute(runnable);
        }

        private void restart(){
            threadPool.shutdownNow();
            instance = new Executor();
        }
    }
}

