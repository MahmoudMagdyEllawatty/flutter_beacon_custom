package com.flutterbeacon;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterBeaconPlugin implements FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

  private static final BeaconParser iBeaconLayout = new BeaconParser()
          .setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24");

  // ✅ Request codes
  public static final int REQUEST_CODE_LOCATION = 1234;
  public static final int REQUEST_CODE_BLUETOOTH = 5678;

  private FlutterBeaconScanner beaconScanner;
  private FlutterBeaconBroadcast beaconBroadcast;
  private FlutterPlatform platform;

  private MethodChannel channel;
  private EventChannel eventChannel;
  private EventChannel eventChannelMonitoring;
  private EventChannel eventChannelBluetoothState;
  private EventChannel eventChannelAuthorizationStatus;

  private Context applicationContext;
  private Activity activity;
  private ActivityPluginBinding activityPluginBinding;
  private BeaconManager beaconManager;

  public Result flutterResult;
  private Result flutterResultBluetooth;
  private EventChannel.EventSink eventSinkLocationAuthorizationStatus;

  public FlutterBeaconPlugin() {}

  // --- FlutterPlugin implementation ---
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    this.applicationContext = binding.getApplicationContext();
    this.beaconManager = BeaconManager.getInstanceForApplication(applicationContext);

    if (!beaconManager.getBeaconParsers().contains(iBeaconLayout)) {
      beaconManager.getBeaconParsers().clear();
      beaconManager.getBeaconParsers().add(iBeaconLayout);
    }

    BinaryMessenger messenger = binding.getBinaryMessenger();

    channel = new MethodChannel(messenger, "flutter_beacon");
    channel.setMethodCallHandler(this);

    eventChannel = new EventChannel(messenger, "flutter_beacon_event");
    eventChannelMonitoring = new EventChannel(messenger, "flutter_beacon_event_monitoring");
    eventChannelBluetoothState = new EventChannel(messenger, "flutter_bluetooth_state_changed");
    eventChannelAuthorizationStatus = new EventChannel(messenger, "flutter_authorization_status_changed");
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    teardownChannels();
  }

  private void teardownChannels() {
    if (activityPluginBinding != null) {
      activityPluginBinding.removeActivityResultListener(this::onActivityResult);
      activityPluginBinding.removeRequestPermissionsResultListener(this::onRequestPermissionsResult);
    }

    platform = null;
    beaconBroadcast = null;
    beaconScanner = null;

    if (channel != null) channel.setMethodCallHandler(null);
    if (eventChannel != null) eventChannel.setStreamHandler(null);
    if (eventChannelMonitoring != null) eventChannelMonitoring.setStreamHandler(null);
    if (eventChannelBluetoothState != null) eventChannelBluetoothState.setStreamHandler(null);
    if (eventChannelAuthorizationStatus != null) eventChannelAuthorizationStatus.setStreamHandler(null);

    channel = null;
    eventChannel = null;
    eventChannelMonitoring = null;
    eventChannelBluetoothState = null;
    eventChannelAuthorizationStatus = null;
  }

  // --- ActivityAware implementation ---
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    this.activityPluginBinding = binding;
    this.activity = binding.getActivity();

    platform = new FlutterPlatform(activity);
    beaconScanner = new FlutterBeaconScanner(this, activity);
    beaconBroadcast = new FlutterBeaconBroadcast(activity, iBeaconLayout);

    eventChannel.setStreamHandler(beaconScanner.rangingStreamHandler);
    eventChannelMonitoring.setStreamHandler(beaconScanner.monitoringStreamHandler);
    eventChannelBluetoothState.setStreamHandler(new FlutterBluetoothStateReceiver(activity));
    eventChannelAuthorizationStatus.setStreamHandler(locationAuthorizationStatusStreamHandler);

    binding.addActivityResultListener(this::onActivityResult);
    binding.addRequestPermissionsResultListener(this::onRequestPermissionsResult);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    if (activityPluginBinding != null) {
      activityPluginBinding.removeActivityResultListener(this::onActivityResult);
      activityPluginBinding.removeRequestPermissionsResultListener(this::onRequestPermissionsResult);
    }
    activityPluginBinding = null;
    activity = null;
  }

  // --- MethodCallHandler implementation ---
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
    if (beaconManager == null || activity == null) {
      result.error("BeaconPlugin", "Plugin is not attached to an Activity or BeaconManager is not initialized.", null);
      return;
    }

    switch (call.method) {
      case "initialize":
        if (!beaconManager.isBound(beaconScanner.beaconConsumer)) {
          this.flutterResult = result;
          this.beaconManager.bind(beaconScanner.beaconConsumer);
        } else {
          result.success(true);
        }
        break;

      case "initializeAndCheck":
        initializeAndCheck(result);
        break;

      case "setScanPeriod":
        try {
          int scanPeriod = call.argument("scanPeriod");
          beaconManager.setForegroundScanPeriod(scanPeriod);
          beaconManager.updateScanPeriods();
          result.success(true);
        } catch (RemoteException e) {
          result.success(false);
        }
        break;

      case "setBetweenScanPeriod":
        try {
          int betweenScanPeriod = call.argument("betweenScanPeriod");
          beaconManager.setForegroundBetweenScanPeriod(betweenScanPeriod);
          beaconManager.updateScanPeriods();
          result.success(true);
        } catch (RemoteException e) {
          result.success(false);
        }
        break;

      case "setLocationAuthorizationTypeDefault":
        result.success(true);
        break;

      case "authorizationStatus":
        result.success(platform.checkLocationServicesPermission() ? "ALLOWED" : "NOT_DETERMINED");
        break;

      case "checkLocationServicesIfEnabled":
        result.success(platform.checkLocationServicesIfEnabled());
        break;

      case "bluetoothState":
        try {
          boolean flag = platform.checkBluetoothIfEnabled();
          result.success(flag ? "STATE_ON" : "STATE_OFF");
        } catch (RuntimeException ignored) {
          result.success("STATE_UNSUPPORTED");
        }
        break;

      case "requestAuthorization":
        if (!platform.checkLocationServicesPermission()) {
          this.flutterResult = result;
          platform.requestAuthorization();
        } else {
          if (eventSinkLocationAuthorizationStatus != null) {
            eventSinkLocationAuthorizationStatus.success("ALLOWED");
          }
          result.success(true);
        }
        break;

      case "openBluetoothSettings":
        if (!platform.checkBluetoothIfEnabled()) {
          this.flutterResultBluetooth = result;
          platform.openBluetoothSettings();
        } else {
          result.success(true);
        }
        break;

      case "openLocationSettings":
        platform.openLocationSettings();
        result.success(true);
        break;

      case "openApplicationSettings":
        result.notImplemented();
        break;

      case "close":
        if (beaconManager != null) {
          beaconScanner.stopRanging();
          beaconManager.removeAllRangeNotifiers();
          beaconScanner.stopMonitoring();
          beaconManager.removeAllMonitorNotifiers();
          if (beaconManager.isBound(beaconScanner.beaconConsumer)) {
            beaconManager.unbind(beaconScanner.beaconConsumer);
          }
        }
        result.success(true);
        break;

      case "startBroadcast":
        beaconBroadcast.startBroadcast(call.arguments, result);
        break;

      case "stopBroadcast":
        beaconBroadcast.stopBroadcast(result);
        break;

      case "isBroadcasting":
        beaconBroadcast.isBroadcasting(result);
        break;

      case "isBroadcastSupported":
        result.success(platform.isBroadcastSupported());
        break;

      default:
        result.notImplemented();
        break;
    }
  }

  // --- Helper methods ---
  private void initializeAndCheck(Result result) {
    if (platform.checkLocationServicesPermission() &&
            platform.checkBluetoothIfEnabled() &&
            platform.checkLocationServicesIfEnabled()) {
      if (result != null) {
        result.success(true);
        return;
      }
    }

    flutterResult = result;
    if (!platform.checkBluetoothIfEnabled()) {
      platform.openBluetoothSettings();
      return;
    }

    if (!platform.checkLocationServicesPermission()) {
      platform.requestAuthorization();
      return;
    }

    if (!platform.checkLocationServicesIfEnabled()) {
      platform.openLocationSettings();
      return;
    }

    if (beaconManager != null && !beaconManager.isBound(beaconScanner.beaconConsumer)) {
      if (result != null) {
        this.flutterResult = result;
      }
      beaconManager.bind(beaconScanner.beaconConsumer);
      return;
    }

    if (result != null) {
      result.success(true);
    }
  }

  private final EventChannel.StreamHandler locationAuthorizationStatusStreamHandler = new EventChannel.StreamHandler() {
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
      eventSinkLocationAuthorizationStatus = events;
    }

    @Override
    public void onCancel(Object arguments) {
      eventSinkLocationAuthorizationStatus = null;
    }
  };

  // --- Activity callbacks ---
  private boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode == REQUEST_CODE_LOCATION) {
      if (flutterResult != null) {
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        flutterResult.success(granted);
        flutterResult = null;
      }
      if (eventSinkLocationAuthorizationStatus != null) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          eventSinkLocationAuthorizationStatus.success("ALLOWED");
        } else {
          eventSinkLocationAuthorizationStatus.success("DENIED");
        }
      }
      return true;
    }
    return false;
  }

  private boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
    boolean bluetoothEnabled = requestCode == REQUEST_CODE_BLUETOOTH && resultCode == Activity.RESULT_OK;
    if (requestCode == REQUEST_CODE_BLUETOOTH) {
      if (flutterResultBluetooth != null) {
        flutterResultBluetooth.success(bluetoothEnabled);
        flutterResultBluetooth = null;
      } else if (flutterResult != null) {
        flutterResult.success(bluetoothEnabled);
        flutterResult = null;
      }
      return true;
    }
    return false;
  }

  public BeaconManager getBeaconManager() {
    return beaconManager;
  }
}