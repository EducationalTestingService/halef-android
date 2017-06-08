package org.ets.halefsipclientandroid;

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
    Callbacks activity;
    private io.socket.client.Socket websocket;
    private String randomCode = "";
    private static final String TAG = "SipClientService";
    private final IBinder mBinder = new LocalBinder();

    private String asteriskDomain, username, password;

    private SipAudioCall call;
    private SipManager mSipManager;
    private SipProfile mSipProfile;
    private SipRegistrationListener mSipRegistrationListener;

    private boolean mRegistered;

    public class LocalBinder extends Binder {
        public SipClientService getService() {
            return SipClientService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void init(String asteriskDomain, String username, String password) {
        this.asteriskDomain = asteriskDomain;
        this.username = username;
        this.password = password;

        Log.d(TAG, "SipClientService initialized with connection information: ");
        Log.d(TAG, " - asteriskDomain: " + asteriskDomain);
        Log.d(TAG, " - username: " + username);
        Log.d(TAG, " - password: " + password);

        // Create SIP account if exists
        createSipManager();
        createLocalProfile();
        createRegistrationListener();
    }

    public void register() {
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

    public void unregister() {
        // Unregister from remote server and free local account
        try {
            mSipManager.close(mSipProfile.getUriString());
            mRegistered = false;
        } catch (SipException se){
            se.printStackTrace();
        }
        Log.d(TAG, "SIPClientService unregistered.");
    }

    private String getRandomCode(){
        //note a single Random object is reused here
        String randomCode = "";
        Random randomGenerator = new Random();
        for (int idx = 1; idx <= 12; ++idx){
            int randomInt = randomGenerator.nextInt(10);
            randomCode += String.valueOf(randomInt);
        }
        return randomCode;
    }

    public int call(String extension) {

        randomCode = getRandomCode();
        String application = extension + randomCode + "@" + asteriskDomain;
        activity.debugMessage("Calling: " + application);

        if (!mRegistered) {
            Log.d(TAG, "Cannot call. We are not registered.");
            return NOT_REGISTERED;
        }

        websocket_connect();
        SipAudioCall.Listener listener = new SipAudioCall.Listener() {

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

        try {
            call = mSipManager.makeAudioCall(mSipProfile.getUriString(), application, listener, 30);
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

    public boolean isRegistered() {
        return mRegistered;
    }

    private void createSipManager(){
        if (mSipManager == null){
            mSipManager = SipManager.newInstance(this);
        }
        Log.d(TAG, "SipManager created.");
    }

    private void createLocalProfile(){
        if (mSipProfile == null) {
            try {
                SipProfile.Builder builder = new SipProfile.Builder(username, asteriskDomain);
                builder.setPassword(password);
                mSipProfile = builder.build();
            } catch (java.text.ParseException pe){
                pe.printStackTrace();
            }
        }
        Log.d(TAG, "LocalSipProfile created.");
    }

    private void createRegistrationListener(){
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
        Log.d(TAG, "RegistrationListener created.");
    }

    private void websocket_connect() {
        IO.Options options = new IO.Options();
        options.path = "/messenger/socketio/socketio";
        try {
            websocket = IO.socket("https://external.halef-research.org", options);
            websocket.on(Socket.EVENT_CONNECT, onWsConnect);
            websocket.on(Socket.EVENT_DISCONNECT, onWsDisconnect);
            websocket.on("custom-api", onWsCustomApi);
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
                obj.put("user", randomCode);
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

    private Emitter.Listener onWsCustomApi = new Emitter.Listener(){
        @Override
        public void call(Object... args) {
            Log.d(TAG, "Websocket recevied custom-api.");
            JSONObject data = (JSONObject) args[0];
            String command;
            String arg1;
            String message;
            //command = data.getString("command");
            //arg1 = data.getString("arg1");
            message = data.toString();
            activity.feedbackMessage(message);
            Log.d(TAG, message);
        }
    };

    public void registerActivity(Activity activity){
        this.activity = (Callbacks)activity;
    }

    public interface Callbacks{
        void registerStatus(int status);
        void callStatus(int status);
        void feedbackMessage(String message);
        void debugMessage(String message);
    }
}
