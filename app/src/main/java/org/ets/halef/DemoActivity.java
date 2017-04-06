package org.ets.halef;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import org.ets.halefsipclientandroid.SipClientService;
import org.ets.halefsipclientandroid.SipClientService.LocalBinder;

public class DemoActivity extends AppCompatActivity {

    private static final String TAG = "DemoActivity";

    private static final int HALEF_PERMISSIONS = 0;
    private SipClientService mSipClientService;
    private boolean mSipClientBound;

    private Button callButton, hangupButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        callButton = (Button) findViewById(R.id.btnCall);
        hangupButton = (Button) findViewById(R.id.btnHangup);

        callButton.setEnabled(false);
        hangupButton.setEnabled(false);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.USE_SIP) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED)) {
            // We need the permissions. Wait for callback before we go on.
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{ Manifest.permission.INTERNET,
                            Manifest.permission.USE_SIP,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.WAKE_LOCK,
                            Manifest.permission.VIBRATE,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.MODIFY_AUDIO_SETTINGS},
                    HALEF_PERMISSIONS);
        } else {
            // We have the permissions. Go ahead.
            Intent i = new Intent(this, SipClientService.class);
            startService(i);
            bindService(i, mSipClientServiceConnection, Context.BIND_AUTO_CREATE);
            callButton.setEnabled(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unbind from SipClientService
        if (mSipClientBound) {
            mSipClientService.unregister();
            unbindService(mSipClientServiceConnection);
            mSipClientBound = false;
        }
    }

    public void onCall(View v){
        if (mSipClientBound) {
            int status = mSipClientService.call("7804");
            callButton.setEnabled(false);
            hangupButton.setEnabled(true);
            Log.d(TAG, "Call status:  " + Integer.toString(status));
        }
    }

    public void onHangup(View v){
        if (mSipClientBound) {
            mSipClientService.hangUp();
            hangupButton.setEnabled(false);
            callButton.setEnabled(true);
            Log.d(TAG, "Call hung up.");
        }
    }

    private ServiceConnection mSipClientServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LocalBinder binder = (LocalBinder) service;
            mSipClientService = binder.getService();
            mSipClientBound = true;
            Log.d(TAG, "SipClientService bound");

            // Initialize service with connection information
            String asteriskDomain = getResources().getString(R.string.asteriskDomain);
            String asteriskUsername = getResources().getString(R.string.asteriskUsername);
            String asteriskPassword = getResources().getString(R.string.asteriskPassword);
            mSipClientService.init(asteriskDomain, asteriskUsername, asteriskPassword);
            mSipClientService.register();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mSipClientBound = false;
            Log.d(TAG, "SipClientService unbound");
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case HALEF_PERMISSIONS: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED
                        && grantResults[2] == PackageManager.PERMISSION_GRANTED
                        && grantResults[3] == PackageManager.PERMISSION_GRANTED
                        && grantResults[4] == PackageManager.PERMISSION_GRANTED
                        && grantResults[5] == PackageManager.PERMISSION_GRANTED
                        && grantResults[6] == PackageManager.PERMISSION_GRANTED) {
                    // We got the permissions. Go ahead.
                    Intent i = new Intent(this, SipClientService.class);
                    startService(i);
                    bindService(i, mSipClientServiceConnection, Context.BIND_AUTO_CREATE);
                    callButton.setEnabled(true);
                } else {
                    Log.d(TAG, "User didn't give use the permissions. What should we do?");
                }
            }
        }
    }

}
