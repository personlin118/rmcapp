/*
Filename: MobileViewerActivity.java
Purpose:
Description:
Notice:

  預設ICON大小：
  drawable-xhdpi
    96x96
  drawable-hdpi
    72x72
  drawable-mdpi
    48x48
  drawable-ldpi
    36x36
    
產生金鑰的指令：    
C:\Program Files\Java\jdk1.6.0_32\bin>keytool -genkey -v -keystore dynamas.keystore -alias dt -keyalg RSA -keysize 2048 -validity 10000
密碼：shengbin

之後放在：C:\Prog\AndroidWorkspace
 

ADT的預設debug key放在：
C:\Documents and Settings\Administrator\.android\debug.keystore

  
ADB命令：
C:\Android\android-sdk\platform-tools>adb kill-server
C:\Android\android-sdk\platform-tools>adb devices

Revision History:
  July 24, 2012 (First created)
  July 30, 2012 (First Release)

工作事項：
*/
package com.dynamas.MobileViewer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
//import android.widget.Toast;
//import android.util.Log;
import android.view.View;
import android.content.Intent;
import android.content.SharedPreferences;


public class MobileViewerActivity extends Activity {
    private static final String DTag = "MVA";
    private static final String PreferencesFilename = "Persist.pref";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        //    Toast.makeText( this, "OnCreate", Toast.LENGTH_SHORT ).show();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.input_address);

        SharedPreferences settings = getSharedPreferences(PreferencesFilename, 0);

        EditText et_ip_addr = (EditText) findViewById(R.id.IA_ET_IPAddr);
        EditText et_user_name = (EditText) findViewById(R.id.IA_ET_UserName);
        EditText et_password = (EditText) findViewById(R.id.IA_ET_Password);

        et_ip_addr.setText(settings.getString("v_address", ""));
        et_user_name.setText(settings.getString("v_user_name", ""));
        et_password.setText(settings.getString("v_password", ""));
    }

    @Override
    protected void onDestroy() {
        //		Toast.makeText( this, "OnDestroy", Toast.LENGTH_SHORT ).show();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        //		Toast.makeText( this, "onPause", Toast.LENGTH_SHORT ).show();
        super.onPause();
    }

    @Override
    protected void onRestart() {
        //		Toast.makeText( this, "onRestart", Toast.LENGTH_SHORT ).show();
        super.onRestart();
    }

    @Override
    protected void onResume() {
        //		Toast.makeText( this, "onResume", Toast.LENGTH_SHORT ).show();
        super.onResume();
    }

    @Override
    protected void onStart() {
        //		Toast.makeText( this, "onStart", Toast.LENGTH_SHORT ).show();
        super.onStart();
    }

    @Override
    protected void onStop() {
        //		Toast.makeText( this, "onStop", Toast.LENGTH_SHORT ).show();
        super.onStop();
    }

    public void action_connect(View view) {
        Intent intent = new Intent();

        intent.setClass(this, LiveVideoActivity.class); // 設定這個intent所指向的類別

        // 尋找control的內容	
        EditText et_ip_addr = (EditText) findViewById(R.id.IA_ET_IPAddr);
        EditText et_user_name = (EditText) findViewById(R.id.IA_ET_UserName);
        EditText et_password = (EditText) findViewById(R.id.IA_ET_Password);

        // 先儲存原本鍵入的資料
        SharedPreferences settings = getSharedPreferences(PreferencesFilename, 0);
        SharedPreferences.Editor editor = settings.edit();

        editor.putString("v_address", et_ip_addr.getText().toString());
        editor.putString("v_user_name", et_user_name.getText().toString());
        editor.putString("v_password", et_password.getText().toString());
        editor.commit();

        // 設定傳遞參數
        Bundle bundle = new Bundle();

        bundle.putString("v_address", et_ip_addr.getText().toString());
        bundle.putString("v_user_name", et_user_name.getText().toString());
        bundle.putString("v_password", et_password.getText().toString());
        intent.putExtras(bundle);

        //    Log.d( DTag, "Begin LiveVideoActivity");
        startActivity(intent);
        //    Log.d( DTag, "End LiveVideoActivity");
    }
}