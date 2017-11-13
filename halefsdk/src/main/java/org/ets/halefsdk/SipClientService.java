package org.ets.halefsdk;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.sip.SipAudioCall;
import android.net.sip.SipException;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipRegistrationListener;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

import io.socket.emitter.Emitter;


import io.socket.client.IO;
import io.socket.client.Socket;

public class SipClientService extends Service {
    public static final int CALL_SUCCESS = 0;
    public static final int NOT_REGISTERED = 1;
    public static final int REGISTERING = 2;
    public static final int REGISTERED = 3;
    public static final int REGISTERING_FAILED = 4;
    public static final int CALL_INPROGRESS = 5;
    public static final int CALL_ENDED = 6;

    private static final String TAG = "SipClientService";

    private final IBinder mBinder = new LocalBinder();

    private boolean mRegistered;
    private Callbacks activity;
    private io.socket.client.Socket websocket;
    private SipAudioCall call;
    private SipManager mSipManager;
    private SipProfile mSipProfile;
    private SipRegistrationListener mSipRegistrationListener;
    private SipAudioCall.Listener mSipAudioCallListener;
    private String mDomain;
    private String mCallUUID;

    /***** Bound service code *****/
    public class LocalBinder extends Binder {
        public SipClientService getService() {
            return SipClientService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    /***** End bound service code *****/

    /***** Public interface *****/
    public void registerActivity(Activity activity){
        this.activity = (Callbacks)activity;
    }

    public interface Callbacks{
        void registerStatus(int status);
        void callStatus(int status);
        void feedbackMessage(String message);
        void debugMessage(String message);
    }

    public void register(String domain, String username, String password) {
        init(domain, username, password);
        doRegister();
    }

    public void unregister() {
        try {
            mSipManager.close(mSipProfile.getUriString());
            mRegistered = false;
        } catch (SipException se){
            se.printStackTrace();
        }
    }

    public int call(String extension) {

        mCallUUID = getCallUUID();
        String application = extension + "0000" + mCallUUID + "@" + mDomain;
        activity.debugMessage("Calling: " + application);

        if (!mRegistered) {
            Log.d(TAG, "Cannot call. We are not registered.");
            return NOT_REGISTERED;
        }

        connectWebsocket();

        try {
            call = mSipManager.makeAudioCall(mSipProfile.getUriString(),
                                             application, mSipAudioCallListener, 30);
        } catch (SipException se) {
            se.printStackTrace();
        }
        return CALL_SUCCESS;
    }

    public void hangUp() {
        if (call != null && call.isInCall()){
            try {
                call.endCall();
            } catch (SipException e) {
                e.printStackTrace();
            }
        }
    }
    /***** End Public interface *****/

    /**** Private helper functions *****/
    private void init(String domain, String username, String password) {
        createSipManager();
        createLocalProfile(domain, username, password);
        createRegistrationListener();
        createSipAudioCallListener();
    }

    private void createSipManager() {
        if (mSipManager == null){
            mSipManager = SipManager.newInstance(this);
        }
    }

    private void createLocalProfile(String domain, String username, String password) {
        mDomain = domain;
        if (mRegistered) {
            unregister();
        }

        try {
            mSipManager.close(username + "@" + domain);
        } catch (SipException e) {
            e.printStackTrace();
        }

        try {
            SipProfile.Builder builder = new SipProfile.Builder(username, domain);
            builder.setPassword(password);
            builder.setProfileName("HALEF");
            mSipProfile = builder.build();
        } catch (java.text.ParseException pe) {
            pe.printStackTrace();
        }
    }

    private void createRegistrationListener() {
        if (mSipRegistrationListener == null) {
            mSipRegistrationListener = new SipRegistrationListener() {

                public void onRegistering(String localProfileUri) {
                    Log.i(TAG, "Registering with server...");
                    activity.registerStatus(REGISTERING);
                }

                public void onRegistrationDone(String localProfileUri, long expiryTime) {
                    Log.i(TAG, "Registered and ready.");
                    mRegistered = true;
                    activity.registerStatus(REGISTERED);
                }

                public void onRegistrationFailed(String localProfileUri, int errorCode,
                                                 String errorMessage) {
                    Log.i(TAG, "Registeration failed. " + errorMessage + " " + Integer.toString(errorCode));
                    activity.registerStatus(REGISTERING_FAILED);
                }
            };
        }
    }

    private void createSipAudioCallListener() {
        if (mSipAudioCallListener == null) {
            mSipAudioCallListener = new SipAudioCall.Listener() {
                @Override
                public void onCallEstablished(SipAudioCall call) {
                    // Start audio for call
                    call.startAudio();

                    // Enable speaker phone and turn up volume
                    AudioManager am = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                    am.setSpeakerphoneOn(true);
                    call.setSpeakerMode(true);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);

                    // If we are muted, disable mute.
                    if (call.isMuted()) {
                        call.toggleMute();
                    }
                    Log.d(TAG, "Call started.");
                    activity.callStatus(CALL_INPROGRESS);
                }

                @Override
                public void onCallEnded(SipAudioCall call) {
                    call.close();
                    Log.d(TAG, "Call ended.");
                    activity.callStatus(CALL_ENDED);
                    websocket.disconnect();
                }
            };
        }
    }

    private void doRegister() {
        try {
            if (!mSipManager.isOpened(mSipProfile.getUriString())){
                Intent intent = new Intent();
                intent.setAction("android.halef.ets.org.INCOMING_CALL");
                PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, Intent.FILL_IN_DATA);
                mSipManager.open(mSipProfile, pendingIntent, null);
                mSipManager.setRegistrationListener(mSipProfile.getUriString(), mSipRegistrationListener);
            }
        } catch(SipException se){
            se.printStackTrace();
        }
    }

    private String getCallUUID(){
        String randomCode = "";
        Random randomGenerator = new Random();
        for (int idx = 0; idx < 9; ++idx){
            int randomInt = randomGenerator.nextInt(10);
            randomCode += String.valueOf(randomInt);
        }
        return randomCode;
    }

    private void connectWebsocket() {
        IO.Options options = new IO.Options();
        options.path = "/messenger/socketio/socketio";
        try {
            websocket = IO.socket("https://external.halef-research.org", options);
            websocket.on(Socket.EVENT_CONNECT, onWsConnect);
            websocket.on(Socket.EVENT_DISCONNECT, onWsDisconnect);
            websocket.on("message", onWsMessage);
        } catch (java.net.URISyntaxException e) {
            Log.d(TAG, "URISyntaxException");
        }
        websocket.connect();
    }

    private Emitter.Listener onWsConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.d(TAG, "Websocket connected.");
            JSONObject obj = new JSONObject();
            try {
                obj.put("user", mCallUUID);
            } catch (JSONException e) {
                Log.e(TAG,e.getMessage());
                return;
            }
            websocket.emit("register", obj);
        }
    };

    private Emitter.Listener onWsDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.d(TAG, "Websocket disconnected.");
        }
    };

    private Emitter.Listener onWsMessage = new Emitter.Listener(){
        @Override
        public void call(Object... args) {
            Log.d(TAG, "Websocket recevied message");
            //JSONObject data = (JSONObject) args[0];
            String message = args[0].toString();
            activity.feedbackMessage(message);
            Log.d(TAG, message);
        }
    };
}
