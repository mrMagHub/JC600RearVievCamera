package com.smigunov.JC600RearViewCamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.smigunov.service.CameraService;

public class AutoStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, CameraService.class));
    }
}
