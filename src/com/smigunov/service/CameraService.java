package com.smigunov.service;

import android.app.Application;
import android.app.Service;
import android.content.*;
import android.hardware.Camera;
import android.os.IBinder;
import android.util.Log;
import android.view.*;
import android.widget.RelativeLayout;
import android.widget.Toast;
import com.smigunov.JC600RearViewCamera.JC600RearViewCamera;
import com.smigunov.JC600RearViewCamera.R;

import java.util.concurrent.TimeUnit;

public class CameraService extends Service {

    public static final String LOG_TAG = "JC600Debug";
    public static final String MSG_CUSTOM_KEYEVENT = "android.intent.action.CUSTOM_KEY_EVENT";
    private static android.view.WindowManager.LayoutParams carbackParams;
    private static RelativeLayout mCarBackLayout;
    private static SurfaceHolder mCarBackSurfaceHolder;
    private static Camera camera;
    private static WindowManager mWindowManager;
    BroadcastReceiver br;
    private volatile boolean mCarBackStarted = false;
    private volatile boolean mAccOn = true;
    private String pckgGPS, activityGPS = "";
    private Integer capturePhoto = 0;
    private Integer delayBoot = 0;
    private Integer delaySleep = 0;
    private Integer delayReverse = 0;
    private volatile boolean optimizeCamSize = false;

    private volatile boolean mCapturePhotoThread = false;
    private volatile boolean mCarbackFrontThread = false;
    private volatile boolean mRecordThread = false;
    private volatile boolean mVideoRecord = false;

    private Camera.Size cameraSize = null;

    public CameraService() {

        br = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {

                String key = intent.getStringExtra("key_flag");
                String fromActivity = intent.getStringExtra(JC600RearViewCamera.INTENT_PARAM);

                if (key != null) {
                    Log.d(LOG_TAG, key);
                }
                if (fromActivity != null) {
                    Log.d(LOG_TAG, fromActivity);
                }

                if ("CARBACKA".equals(key)) {
                    if (!mCarBackStarted) {
                        mCarBackStarted = true;
                        doCarbackFront();
                    }
                } else if ("CARBACKB".equals(key)) {
                    mCarBackStarted = false;
                    doCarbackHide(true);
                } else if ("ACCON".equals(key)) {
                    mAccOn = true;
                    // запуск записи
                    startRecord(delaySleep);
                    mVideoRecord = true;
                } else if ("ACCOFF".equals(key)) {
                    mAccOn = false;

                    if (mCarBackStarted) {
                        doCarbackHide(false);
                    }
                    // остановка записи
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.StopRecord"));
                    mVideoRecord = false;
                    // Активация съемки фото
                    capturePhoto();
                } else if ("HOME_KEY".equals(key) ||
                        ("BACK_KEY".equals(key) ||
                                JC600RearViewCamera.INTENT_STOP_CAMERA.equals(fromActivity))) {
                    if (mCarBackStarted) {
                        doCarbackHide(true);
                    }
                } else if (JC600RearViewCamera.INTENT_CHANGE_SETTINGS.equals(fromActivity)) {
                    reloadSettings();
                } else if ("DVR_KEY".equals(key)) {
                    // Показ главного окна
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.ShowMainWindow"));
                } else if ("GPS_KEY".equals(key) && !"".equals(pckgGPS) && !"".equals(activityGPS)) {
                    try {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pckgGPS);
                        if (launchIntent != null) {
                            startActivity(launchIntent);
                        } else {
                            Intent extIntent = new Intent();
                            extIntent.setComponent(new ComponentName(pckgGPS, activityGPS));
                            extIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            extIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(extIntent);
                        }
                    } catch (Exception e) {
                        Toast.makeText(CameraService.this,
                                "Ошибка запуска программы: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        Log.d("CameraService", "GPS_KEY error " + e.getMessage());
                    }
                }
            }
        };
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {

        super.onCreate();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MSG_CUSTOM_KEYEVENT);
        intentFilter.addAction(JC600RearViewCamera.BROADCAST_ACTION);

        registerReceiver(br, intentFilter);
        createCarBackFloatView();
        reloadSettings();
        startRecord(delayBoot);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(br);

        if (camera != null) {
            camera.release();
            camera = null;
        }
        if (mCarBackLayout != null) {
            mWindowManager.removeView(mCarBackLayout);
            mCarBackLayout = null;
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createCarBackFloatView() {
        Log.d("CameraService", "createCarBackFloatView");
        if (mCarBackLayout != null) {
            return;
        }
        Application application = getApplication();
        getApplication();
        mWindowManager = (WindowManager) application.getSystemService(Context.WINDOW_SERVICE);
        carbackParams = new android.view.WindowManager.LayoutParams();
        carbackParams.type = 2002;
        carbackParams.format = 1;
        carbackParams.flags = 8;
        carbackParams.gravity = 17;
        carbackParams.width = 1;
        carbackParams.height = 1;

        mCarBackLayout = (RelativeLayout) LayoutInflater.from(getApplication()).inflate(R.layout.carback, null);
        SurfaceView mCarBackSurfaceView = (SurfaceView) mCarBackLayout.findViewById(R.id.sv_carback_surfaceview);

        // создаем обработчик нажатия
        View.OnClickListener oclick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doCarbackHide(true);
            }
        };
        mCarBackSurfaceView.setOnClickListener(oclick);

        mCarBackSurfaceHolder = mCarBackSurfaceView.getHolder();
        mCarBackSurfaceHolder.addCallback(new android.view.SurfaceHolder.Callback() {

            public void surfaceChanged(SurfaceHolder surfaceholder, int i, int width, int height) {
            }

            public void surfaceCreated(SurfaceHolder surfaceholder) {
            }

            public void surfaceDestroyed(SurfaceHolder surfaceholder) {
                releaseCamera();
            }
        });

        mWindowManager.addView(mCarBackLayout, carbackParams);
    }

    private void doCarbackFront() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                if (mCarbackFrontThread) {
                    Log.d("CameraService", "doCarbackFront return");
                    return;
                }

                Log.d("CameraService", "doCarbackFront");

                mCarbackFrontThread = true;
                boolean init = false;

                if (mVideoRecord) {
                    // Остановка записи
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.StopRecord"));
                    // скрыть главное окно
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.HideMainWindow"));
                    Log.d("CameraService", "rubberbigpepper.VideoReg.StopRecord");
                }

                boolean firstIteration = true;

                while (!init && mCarBackStarted) {
                    try {
                        if (mVideoRecord) {
                            Log.d("CameraService", "releaseCamera wait 500");
                            if (firstIteration) {
                                TimeUnit.MILLISECONDS.sleep(500);
                            } else {
                                TimeUnit.MILLISECONDS.sleep(100);
                            }
                        }

                        initCamera();

                        // Потом отрываем окно, обязательно после initCamera
                        if (mCarBackStarted) {
                            carbackParams.width = -1;
                            carbackParams.height = -1;
                        }

                        init = true;
                        mVideoRecord = false;
                    } catch (Exception e) {
                        Log.d("CameraService", "releaseCamera error " + e.getMessage());
                        e.printStackTrace();
                    }
                    firstIteration = false;
                }

                mCarbackFrontThread = false;

            }
        }).start();
    }

    private void doCarbackHide(Boolean startRecord) {
        carbackParams.width = 1;
        carbackParams.height = 1;
        carbackParams.x = 1000;
        carbackParams.y = 1000;

        mWindowManager.updateViewLayout(mCarBackLayout, carbackParams);

        releaseCamera();

        // Запись
        if (startRecord) {
            startRecord(delayReverse);
        }
    }

    private void initCamera() throws Exception {
        if (camera == null) {
            int CAMERA_ID = 1;
            camera = Camera.open(CAMERA_ID);

            if (optimizeCamSize) {
                if (cameraSize == null) {
                    cameraSize = getBestPreviewSize(camera);
                }

                Camera.Parameters parameters = camera.getParameters();
                parameters.setPreviewSize(cameraSize.width, cameraSize.height);
                camera.setParameters(parameters);
            }
            camera.setDisplayOrientation(0);

            camera.setPreviewDisplay(mCarBackSurfaceHolder);
            camera.startPreview();
            Log.d("CameraService", "initCamera");
        }
    }

    protected void releaseCamera() {
        boolean released = false;
        int releaseCount = 5;

        while (!released && releaseCount > 0) {
            if (camera != null) {
                try {
                    Log.d("CameraService", "releaseCamera");
                    camera.stopPreview();
                    camera.release();
                    camera = null;
                    released = true;
                } catch (Exception e) {
                    Log.d("CameraService", "releaseCamera error " + e.getMessage());
                    e.printStackTrace();
                    try {
                        TimeUnit.MILLISECONDS.sleep(50);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                }
            }
            releaseCount--;
        }
    }

    private void reloadSettings() {

        SharedPreferences mSettings = getSharedPreferences(JC600RearViewCamera.APP_PREFERENCES, Context.MODE_MULTI_PROCESS);

        Log.d("CameraService", "reloadSettings " + mSettings.getAll());

        pckgGPS = mSettings.getString(JC600RearViewCamera.GPS_PACKAGE, "");
        activityGPS = mSettings.getString(JC600RearViewCamera.GPS_ACTIVITY, "");

        capturePhoto = mSettings.getInt(JC600RearViewCamera.CAPTURE_PHOTO, 0);
        delayBoot = mSettings.getInt(JC600RearViewCamera.DELAY_BOOT, 10);
        delaySleep = mSettings.getInt(JC600RearViewCamera.DELAY_SLEEP, 1);
        delayReverse = mSettings.getInt(JC600RearViewCamera.DELAY_REVERSE, 5);
        optimizeCamSize = mSettings.getBoolean(JC600RearViewCamera.OPT_CAMSIZE, false);
    }

    private void capturePhoto() {

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (mCapturePhotoThread) {
                    return;
                }
                if (capturePhoto == 0) {
                    return;
                }

                mCapturePhotoThread = true;
                Log.d("CameraService", "capturePhoto start");

                try {
                    TimeUnit.SECONDS.sleep(30);

                    while (!mCarBackStarted && !mAccOn) {

                        sendBroadcast(new Intent("rubberbigpepper.VideoReg.CapturePhoto"));
                        TimeUnit.SECONDS.sleep(capturePhoto);
                    }
                } catch (InterruptedException e) {
                    Log.d("CameraService", "capturePhoto error " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    mCapturePhotoThread = false;
                }
            }
        }).start();
    }

    private synchronized void updateWindowManager() {
        mWindowManager.updateViewLayout(mCarBackLayout, carbackParams);
    }

    private void startRecord(Integer delay) {

        new Thread(new Runnable() {
            @Override
            public void run() {

                if (mRecordThread) {
                    return;
                }
                Log.d("CameraService", "doCarbackHide");
                mRecordThread = true;

                if (delay != null && delay > 0) {
                    try {
                        TimeUnit.SECONDS.sleep(delay);
                    } catch (InterruptedException e) {
                        Log.d("CameraService", "doCarbackHide error " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                if (!mCarBackStarted && mAccOn) {
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.StartRecord"));
                    mVideoRecord = true;
                    Log.d("CameraService", "doCarbackHide rubberbigpepper.VideoReg.StartRecord");
                }
                mRecordThread = false;
            }
        }).start();
    }


    private Camera.Size getBestPreviewSize(Camera camera) {
        Camera.Size result = null;
        Camera.Parameters p = camera.getParameters();
        Display display = mWindowManager.getDefaultDisplay();

        int width = display.getWidth();
        int height = display.getHeight();

        for (Camera.Size size : p.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea > resultArea) {
                        result = size;
                    }
                }
            }
        }
        return result;
    }

}

