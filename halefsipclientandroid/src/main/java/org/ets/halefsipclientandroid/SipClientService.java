package org.ets.halefsipclientandroid;

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

public class SipClientService extends Service {

    public static final int CALL_SUCCESS = 0;
    public static final int NOT_REGISTERED = 1;

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

    public int call(String extension) {

        String application = extension + "@" + asteriskDomain;

        if (!mRegistered) {
            Log.d(TAG, "Cannot call. We are not registered.");
            return NOT_REGISTERED;
        }

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
            }

            @Override
            public void onCallEnded(SipAudioCall call) {
                call.close();
                Log.d(TAG, "Call ended.");
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
                }

                public void onRegistrationDone(String localProfileUri, long expiryTime) {
                    Log.i(TAG, "Registered and ready.");
                    mRegistered = true;
                }

                public void onRegistrationFailed(String localProfileUri, int errorCode,
                                                 String errorMessage) {
                    Log.i(TAG, "Registeration failed. " + errorMessage + " " + Integer.toString(errorCode));
                }
            };
        }
        Log.d(TAG, "RegistrationListener created.");
    }
}
