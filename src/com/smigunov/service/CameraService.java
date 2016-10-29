package com.smigunov.service;

import android.app.Application;
import android.app.Service;
import android.content.*;
import android.hardware.Camera;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
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
    Handler handler;
    private volatile boolean mCarBackStarted = false;
    private volatile boolean mCameraViewStarted = false;
    private volatile boolean mAccOn = true;
    private String pckgGPS, activityGPS = "";
    private Integer capturePhoto = 0;
    private Integer delayBoot = 0;
    private Integer delaySleep = 0;
    private Integer delayReverse = 0;
    private volatile boolean optimizeCamSize = false;

    private Thread doCapturePhotoThread = null;
    private Thread doCarbackFrontThread = null;
    private Thread doReleaseCameraThread = null;
    private Thread doStartRecordThread = null;
    private volatile boolean mCarbackFrontThread = false;
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
                    if (!mCarBackStarted && !mCameraViewStarted) {
                        mCarBackStarted = true;
                        doCarbackFront();
                    }
                } else if ("CARBACKB".equals(key)) {
                    if (!mCameraViewStarted) {
                        mCarBackStarted = false;
                        doCarbackHide(true);
                    }
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
                    Log.d("CameraService", "rubberbigpepper.VideoReg.StopRecord");
                    mVideoRecord = false;
                    // Активация съемки фото
                    capturePhoto();
                } else if ("HOME_KEY".equals(key) ||
                        ("BACK_KEY".equals(key) ||
                                JC600RearViewCamera.INTENT_STOP_CAMERA.equals(fromActivity))) {

                    mCameraViewStarted = false;
                    mCarBackStarted = false;
                    doCarbackHide(true);
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
                } else if (JC600RearViewCamera.INTENT_START_CAMERA.equals(fromActivity)) {
                    mCameraViewStarted = true;
                    mCarBackStarted = true;
                    doCarbackFront();
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

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                updateWindowManager();
            }
        };

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
        carbackParams.type = WindowManager.LayoutParams.TYPE_PHONE;
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
                Intent intent = new Intent(JC600RearViewCamera.BROADCAST_ACTION);
                intent.putExtra(JC600RearViewCamera.INTENT_PARAM, JC600RearViewCamera.INTENT_STOP_CAMERA);
                sendBroadcast(intent);
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
                releaseCamera(true);
            }
        });

        mWindowManager.addView(mCarBackLayout, carbackParams);
    }

    private void doCarbackFront() {

        if (doReleaseCameraThread != null && doReleaseCameraThread.isAlive()) {
            Log.d("CameraService", "releaseCamera interrupt");
            doReleaseCameraThread.interrupt();
        }

        doCarbackFrontThread = new Thread(new Runnable() {
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
                            Log.d("CameraService", "doCarbackFront wait");
                            if (firstIteration) {
                                TimeUnit.MILLISECONDS.sleep(100);
                            } else {
                                TimeUnit.MILLISECONDS.sleep(50);
                            }
                        }

                        initCamera();

                        // Потом отрываем окно, обязательно после initCamera
                        if (mCarBackStarted) {
                            carbackParams.width = -1;
                            carbackParams.height = -1;
                            handler.sendMessage(new Message());
                        }

                        init = true;
                        mVideoRecord = false;
                    } catch (Exception e2) {
                        Log.d("CameraService", "doCarbackFront error " + e2.getMessage());
                        e2.printStackTrace();
                    }
                    firstIteration = false;
                }

                mCarbackFrontThread = false;
                Log.d("CameraService", "doCarbackFront end");
            }
        });

        doCarbackFrontThread.start();
    }

    private void doCarbackHide(Boolean startRecord) {
        carbackParams.width = 1;
        carbackParams.height = 1;
        carbackParams.x = 1000;
        carbackParams.y = 1000;

        mWindowManager.updateViewLayout(mCarBackLayout, carbackParams);

        releaseCamera(startRecord);
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
        } else {
            Log.d("CameraService", "initCamera - is init");
        }
    }

    protected void releaseCamera(final Boolean startRecord) {

        if (doReleaseCameraThread != null && doReleaseCameraThread.isAlive()) {
            Log.d("CameraService", "releaseCamera interrupt");
            doReleaseCameraThread.interrupt();
        }

        doReleaseCameraThread = new Thread(new Runnable() {
            @Override
            public void run() {

                Log.d("CameraService", "releaseCamera run");

                if (doCarbackFrontThread != null && doCarbackFrontThread.isAlive()) {
                    try {
                        doCarbackFrontThread.join();
                    } catch (InterruptedException e) {
                        Log.d("CameraService", "releaseCamera interrupted return");
                        return;
                    }
                }

                if (Thread.interrupted() || mCarBackStarted) {
                    Log.d("CameraService", "releaseCamera interrupted return");
                    return;
                }

                if (delayReverse != null && delayReverse > 0) {
                    try {
                        TimeUnit.SECONDS.sleep(delayReverse);
                    } catch (InterruptedException e) {
                        Log.d("CameraService", "releaseCamera interrupted return");
                        return;
                    }
                }

                boolean released = false;
                int releaseCount = 50;

                while (!released && releaseCount > 0 && !Thread.interrupted()) {
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
                            TimeUnit.MILLISECONDS.sleep(100);
                        } catch (InterruptedException e1) {
                            Log.d("CameraService", "releaseCamera interrupted return");
                            return;
                        }
                    }
                    releaseCount--;
                }

                // Запись
                if (startRecord) {
                    try {
                        TimeUnit.SECONDS.sleep(3);
                        if (!Thread.interrupted()) {
                            startRecord(0).join();
                        }
                    } catch (InterruptedException e) {
                        Log.d("CameraService", "releaseCamera interrupted return");
                    }
                }

            }
        });

        doReleaseCameraThread.start();
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
        if (doCapturePhotoThread != null && doCapturePhotoThread.isAlive()) {
            Log.d("CameraService", "capturePhoto interrupt");
            doCapturePhotoThread.interrupt();
        }

        doCapturePhotoThread = new Thread(new Runnable() {
            @Override
            public void run() {

                if (capturePhoto == 0) {
                    return;
                }

                Log.d("CameraService", "capturePhoto start");

                try {
                    TimeUnit.SECONDS.sleep(30);

                    while (!mCarBackStarted && !mAccOn && !Thread.interrupted()) {

                        sendBroadcast(new Intent("rubberbigpepper.VideoReg.CapturePhoto"));
                        TimeUnit.SECONDS.sleep(capturePhoto);
                    }
                } catch (InterruptedException e) {
                    Log.d("CameraService", "capturePhoto Interrupted ");
                }
            }
        });

        doCapturePhotoThread.start();
    }

    private synchronized void updateWindowManager() {
        mWindowManager.updateViewLayout(mCarBackLayout, carbackParams);
    }

    private Thread startRecord(final Integer delay) {

        if (doStartRecordThread != null && doStartRecordThread.isAlive()) {
            Log.d("CameraService", "startRecord interrupt");
            doStartRecordThread.interrupt();
        }

        doStartRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {

                if (delay != null && delay > 0) {
                    try {
                        TimeUnit.SECONDS.sleep(delay);
                    } catch (InterruptedException e) {
                        Log.d("CameraService", "startRecord interrupted " + e.getMessage());
                        return;
                    }
                }

                if (!mCarBackStarted && mAccOn && !Thread.interrupted()) {
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.StartRecord"));
                    sendBroadcast(new Intent("rubberbigpepper.VideoReg.HideMainWindow"));
                    mVideoRecord = true;
                    Log.d("CameraService", "doCarbackHide rubberbigpepper.VideoReg.StartRecord + HideMainWindow");
                }
            }
        });

        doStartRecordThread.start();
        return doStartRecordThread;
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

