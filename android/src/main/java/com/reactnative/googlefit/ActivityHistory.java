/**
 * Copyright (c) 2017-present, Stanislav Doskalenko - doskalenko.s@gmail.com
 * All rights reserved.
 *
 * This source code is licensed under the MIT-style license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * Based on Asim Malik android source code, copyright (c) 2015
 *
 **/

package com.reactnative.googlefit;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.Session;
import com.google.android.gms.fitness.request.DataSourcesRequest;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.request.SessionInsertRequest;
import com.google.android.gms.fitness.result.DataReadResult;
import com.google.android.gms.fitness.result.DataSourcesResult;
import com.google.android.gms.fitness.data.Device;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.text.DateFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.ArrayList;


import static com.google.android.gms.fitness.data.Device.TYPE_WATCH;

public class ActivityHistory {

    private ReactContext mReactContext;
    private GoogleFitManager googleFitManager;

    private static final String STEPS_FIELD_NAME = "steps";
    private static final String DISTANCE_FIELD_NAME = "distance";
    private static final String HIGH_LONGITUDE = "high_longitude";
    private static final String LOW_LONGITUDE = "low_longitude";
    private static final String HIGH_LATITUDE = "high_latitude";
    private static final String LOW_LATITUDE = "low_latitude";


    private static final int KCAL_MULTIPLIER = 1000;
    private static final int ONGOING_ACTIVITY_MIN_TIME_FROM_END = 10 * 60000;
    private static final String CALORIES_FIELD_NAME = "calories";

    private static final String TAG = "RNGoogleFit";

    public ActivityHistory(ReactContext reactContext, GoogleFitManager googleFitManager){
        this.mReactContext = reactContext;
        this.googleFitManager = googleFitManager;
    }

    public ReadableArray getActivitySamples(long startTime, long endTime) {
        WritableArray results = Arguments.createArray();
        DataReadRequest readRequest = new DataReadRequest.Builder()
                .aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
                .aggregate(DataType.TYPE_CALORIES_EXPENDED, DataType.AGGREGATE_CALORIES_EXPENDED)
                .aggregate(DataType.TYPE_DISTANCE_DELTA, DataType.AGGREGATE_DISTANCE_DELTA)
                .bucketByActivitySegment(1, TimeUnit.SECONDS)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();

        DataReadResult dataReadResult = Fitness.HistoryApi.readData(googleFitManager.getGoogleApiClient(), readRequest).await(1, TimeUnit.MINUTES);

        List<Bucket> buckets = dataReadResult.getBuckets();
        for (Bucket bucket : buckets) {
            String activityName = bucket.getActivity();
            int activityType = bucket.getBucketType();
            if (!bucket.getDataSets().isEmpty()) {
                long start = bucket.getStartTime(TimeUnit.MILLISECONDS);
                long end = bucket.getEndTime(TimeUnit.MILLISECONDS);
                Date startDate = new Date(start);
                Date endDate = new Date(end);
                WritableMap map = Arguments.createMap();
                map.putDouble("start",start);
                map.putDouble("end",end);
                map.putString("activityName", activityName);
                String deviceName = "";
                String sourceId = "";
                boolean isTracked = true;
                for (DataSet dataSet : bucket.getDataSets()) {
                    for (DataPoint dataPoint : dataSet.getDataPoints()) {
                        try {
                            int deviceType = dataPoint.getOriginalDataSource().getDevice().getType();
                            if (deviceType == TYPE_WATCH) {
                                deviceName = "Android Wear";
                            } else {
                                deviceName = "Android";
                            }
                        } catch (Exception e) {
                        }
                        sourceId = dataPoint.getOriginalDataSource().getAppPackageName();
                        if (startDate.getTime() % 1000 == 0 && endDate.getTime() % 1000 == 0) {
                            isTracked = false;
                        }
                        for (Field field : dataPoint.getDataType().getFields()) {
                            String fieldName = field.getName();
                            switch (fieldName) {
                                case STEPS_FIELD_NAME:
                                    map.putInt("quantity", dataPoint.getValue(field).asInt());
                                    break;
                                case DISTANCE_FIELD_NAME:
                                    map.putDouble(fieldName, dataPoint.getValue(field).asFloat());
                                    break;
                                case CALORIES_FIELD_NAME:
                                    map.putDouble(fieldName, dataPoint.getValue(field).asFloat());
                                default:
                                    Log.w(TAG, "don't specified and handled: " + fieldName);
                            }
                        }
                    }
                }
                map.putString("device", deviceName);
                map.putString("sourceName", deviceName);
                map.putString("sourceId", sourceId);
                map.putBoolean("tracked", isTracked);
                results.pushMap(map);
            }
        }
        
        return results;
    }

    /**
     * Submits the workout to Google Fit.
     * // TODO: Create data class for options.
     *
     * @param options ReadableMap from JS with workout options.
     *                startTime     - number - required - Value in milliseconds
     *                endTime       - number - required - Value in milliseconds
     *                activityType  - string - required - One of activity types
     *                name          - string - optional - workout name
     *                description   - string - optional
     *                activeTime    - number - optional - value in milliseconds
     *                calories      - number - optional
     */
    void submitWorkout(ReadableMap options) throws Exception {
        // Let's check that all required parameters exist
        if (!options.hasKey("startTime")) {
            throw new Exception("startTime is required in options");
        }

        if(!options.hasKey("endTime")) {
            throw new Exception("endTime is required in options");
        }

        if(!options.hasKey("activityType")) {
            throw new Exception("activityType is required in options");
        }

        long startTime = (long) options.getDouble("startTime");
        long endTime =  (long) options.getDouble("endTime");
        String activityType = options.getString("activityType");

        Session.Builder sessionBuilder = new Session.Builder()
                .setActivity(activityType)
                .setIdentifier(UUID.randomUUID().toString())
                .setStartTime(startTime, TimeUnit.MILLISECONDS)
                .setEndTime(endTime, TimeUnit.MILLISECONDS);

        // Add all optional parameters to the session builder
        if(options.hasKey("name")) {
            sessionBuilder.setName(options.getString("name"));
        }

        if(options.hasKey("description")) {
            sessionBuilder.setDescription(options.getString("description"));
        }

        if(options.hasKey("activeTime")) {
            sessionBuilder.setActiveTime((long) options.getDouble("activeTime"), TimeUnit.MILLISECONDS);
        }

        Session session = sessionBuilder.build();

        SessionInsertRequest.Builder insertRequestBuilder = new SessionInsertRequest.Builder()
                .setSession(session);

        // Handle Calories
        if(options.hasKey("calories")) {
            float calories = (float) options.getDouble("calories");

            DataSource caloriesDataSource = new DataSource.Builder()
                    .setAppPackageName(GoogleFitPackage.PACKAGE_NAME)
                    .setDataType(DataType.TYPE_CALORIES_EXPENDED)
                    .setType(DataSource.TYPE_RAW)
                    .build();

            DataSet caloriesDataSet = DataSet.create(caloriesDataSource);
            DataPoint caloriesDataPoint = caloriesDataSet.createDataPoint().setTimeInterval(startTime, endTime, TimeUnit.MILLISECONDS);
            caloriesDataPoint.getValue(Field.FIELD_CALORIES).setFloat(calories);
            caloriesDataSet.add(caloriesDataPoint);

            insertRequestBuilder.addDataSet(caloriesDataSet);
        }

        SessionInsertRequest insertRequest = insertRequestBuilder.build();
        Fitness.getSessionsClient(this.mReactContext, GoogleSignIn.getLastSignedInAccount(this.mReactContext))
                .insertSession(insertRequest)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // At this point, the session has been inserted and can be read.
                        Log.i(TAG, "Session insert was successful!");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.i(TAG, "There was a problem inserting the session: " +
                                e.getLocalizedMessage());
                    }
                });
    }
}
