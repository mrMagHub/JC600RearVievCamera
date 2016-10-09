package com.smigunov.JC600RearViewCamera;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import com.smigunov.service.CameraService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JC600RearViewCamera extends Activity {

    public final static String BROADCAST_ACTION = "com.smigunov.JC600RearViewCamera.CameraService";
    public final static String INTENT_PARAM = "FROM_ACTIVITY";
    public final static String INTENT_STOP_CAMERA = "INTENT_STOP_CAMERA";
    public final static String INTENT_START_CAMERA = "INTENT_START_CAMERA";
    public final static String INTENT_CHANGE_SETTINGS = "INTENT_CHANGE_SETTINGS";

    // Настройки
    public static final String APP_PREFERENCES = "JC600RearViewCamera";
    // GPS
    public static String GPS_USER_LABEL = "GPS_USER_LABEL";
    public static String GPS_PACKAGE = "GPS_PACKAGE";
    public static String GPS_ACTIVITY = "GPS_ACTIVITY";
    // Фото
    public static String CAPTURE_PHOTO = "CAPTURE_PHOTO";
    public static String DELAY_BOOT = "DELAY_BOOT";
    public static String DELAY_SLEEP = "DELAY_SLEEP";
    public static String DELAY_REVERSE = "DELAY_REVERSE";
    public static String OPT_CAMSIZE = "OPT_CAMSIZE";

    private SharedPreferences mSettings;
    private Map<String, String> appsMap = new HashMap<>();

    private void activateCamera(String intentParam) {
        Intent intent = new Intent(JC600RearViewCamera.BROADCAST_ACTION);
        intent.putExtra(INTENT_PARAM, intentParam);
        sendBroadcast(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(com.smigunov.JC600RearViewCamera.R.layout.main);


        mSettings = getSharedPreferences(APP_PREFERENCES, Context.MODE_MULTI_PROCESS);

        // Список установленных программ
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = this.getPackageManager();
        ArrayList<ResolveInfo> list = (ArrayList<ResolveInfo>)
                pm.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        adapter.add("No Selected");

        for (ResolveInfo rInfo : list) {
            String appLabelName = rInfo.activityInfo.applicationInfo.loadLabel(pm).toString();
            appsMap.put(appLabelName, rInfo.activityInfo.packageName + ":" + rInfo.activityInfo.name);
            adapter.add(appLabelName);
        }

        final Spinner spinnerGPS = (Spinner) findViewById(com.smigunov.JC600RearViewCamera.R.id.spinnerGPS);
        spinnerGPS.setAdapter(adapter);
        spinnerGPS.setOnItemSelectedListener(null);
        ;

        restoreSettings((Spinner) findViewById(com.smigunov.JC600RearViewCamera.R.id.spinnerGPS), "spinnerGPS");

        // устанавливаем обработчик нажатия spinnerGPS
        spinnerGPS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                saveSettings(spinnerGPS.getSelectedItem().toString(), GPS_USER_LABEL, GPS_PACKAGE, GPS_ACTIVITY);
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        checkService();

        EditText capturePhoto = (EditText) findViewById(R.id.capturePhoto);
        capturePhoto.setText(String.valueOf(mSettings.getInt(CAPTURE_PHOTO, 0)));

        EditText delayBoot = (EditText) findViewById(R.id.delayBoot);
        delayBoot.setText(String.valueOf(mSettings.getInt(DELAY_BOOT, 10)));

        EditText delaySleep = (EditText) findViewById(R.id.delaySleep);
        delaySleep.setText(String.valueOf(mSettings.getInt(DELAY_SLEEP, 1)));

        EditText delayReverse = (EditText) findViewById(R.id.delayReverse);
        delayReverse.setText(String.valueOf(mSettings.getInt(DELAY_REVERSE, 5)));

        CheckBox chOptCamSize = (CheckBox) findViewById(R.id.chOptimizeCamSize);
        chOptCamSize.setChecked(mSettings.getBoolean(OPT_CAMSIZE, false));

    }

    @Override
    protected void onStop() {
        super.onStop();

        SharedPreferences.Editor editor = mSettings.edit();

        EditText capturePhoto = (EditText) findViewById(R.id.capturePhoto);
        editor.putInt(CAPTURE_PHOTO, Integer.valueOf(capturePhoto.getText().toString()));

        EditText delayBoot = (EditText) findViewById(R.id.delayBoot);
        editor.putInt(DELAY_BOOT, Integer.valueOf(delayBoot.getText().toString()));

        EditText delaySleep = (EditText) findViewById(R.id.delaySleep);
        editor.putInt(DELAY_SLEEP, Integer.valueOf(delaySleep.getText().toString()));

        EditText delayReverse = (EditText) findViewById(R.id.delayReverse);
        editor.putInt(DELAY_REVERSE, Integer.valueOf(delayReverse.getText().toString()));

        CheckBox chOptCamSize = (CheckBox) findViewById(R.id.chOptimizeCamSize);
        editor.putBoolean(OPT_CAMSIZE, chOptCamSize.isChecked());

        editor.commit();

        Intent intent = new Intent(JC600RearViewCamera.BROADCAST_ACTION);
        intent.putExtra(INTENT_PARAM, INTENT_CHANGE_SETTINGS);
        sendBroadcast(intent);
    }

    private void checkService() {
        // Стартуем сервис, если он ещё не запущен ;)
        boolean tStartService = true;
        ActivityManager am = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> rs = am.getRunningServices(50);

        for (ActivityManager.RunningServiceInfo rsi : rs) {
            if (CameraService.class.getName().equalsIgnoreCase(rsi.service.getClassName())) {
                tStartService = false;
                break;
            }
        }

        if (tStartService) {
            startService(new Intent(JC600RearViewCamera.this, CameraService.class));
        }
    }

    private void saveSettings(String selectedItem,
                              String labelKey,
                              String packageKey,
                              String activityKey
    ) {
        if (labelKey == null) {
            return;
        }

        String appParams = appsMap.get(selectedItem);
        // Запоминаем данные
        SharedPreferences.Editor editor = mSettings.edit();
        editor.putString(labelKey, selectedItem);
        editor.putString(packageKey, appParams != null ? appParams.substring(0, appParams.indexOf(":")) : "");
        editor.putString(activityKey, appParams != null ? appParams.substring(appParams.indexOf(":") + 1, appParams.length()) : "");
        editor.commit();

        Intent intent = new Intent(JC600RearViewCamera.BROADCAST_ACTION);
        intent.putExtra(INTENT_PARAM, INTENT_CHANGE_SETTINGS);
        sendBroadcast(intent);
    }

    private void restoreSettings(Spinner spinner, String spinnerName) {

        if (spinner == null) {
            return;
        }

        String settingValue = "";
        if ("spinnerGPS".equals(spinnerName)) {
            settingValue = mSettings.getString(GPS_USER_LABEL, "");
        }

        if ("".equals(settingValue)) {
            return;
        }

        for (int i = 0; i <= spinner.getCount(); i++) {
            if (settingValue.equals(spinner.getAdapter().getItem(i))) {
                spinner.setSelection(i);
                break;
            }
        }
    }

    @Override
    public void onBackPressed() {
        Log.d("JC600RearViewCamera", "onBackPressed");
        activateCamera(INTENT_STOP_CAMERA);
        super.onBackPressed();
    }

    public void onStartCameraClick(View view) {
        Intent intent = new Intent(JC600RearViewCamera.BROADCAST_ACTION);
        intent.putExtra(INTENT_PARAM, INTENT_START_CAMERA);
        sendBroadcast(intent);
    }
}
