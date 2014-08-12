package com.qualoutdoor.recorder.recording;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.example.projet_qualoutdoor_client.OnTaskCompleted;
import com.qualoutdoor.recorder.network.DataSendingManager;
import com.qualoutdoor.recorder.network.EmailFileSender;
import com.qualoutdoor.recorder.network.FileToUpload;
import com.qualoutdoor.recorder.network.SendCompleteListener;
import com.qualoutdoor.recorder.LocalBinder;
import com.qualoutdoor.recorder.LocalServiceConnection;
import com.qualoutdoor.recorder.MyConstants;
import com.qualoutdoor.recorder.R;
import com.qualoutdoor.recorder.location.LocationService;
import com.qualoutdoor.recorder.notifications.NotificationCenter;
import com.qualoutdoor.recorder.persistent.CollectMeasureException;
import com.qualoutdoor.recorder.persistent.DataBaseException;
import com.qualoutdoor.recorder.persistent.FileGenerator;
import com.qualoutdoor.recorder.persistent.FileReadyListener;
import com.qualoutdoor.recorder.persistent.MeasureContext;
import com.qualoutdoor.recorder.persistent.SQLConnector;
import com.qualoutdoor.recorder.telephony.ICellInfo;
import com.qualoutdoor.recorder.telephony.TelephonyListener;
import com.qualoutdoor.recorder.telephony.TelephonyService;

/**
 * This service when started will link to the TelephonyService and begin
 * sampling the phone state. One can bind to this service in order to modify the
 * sampling rate. It is needed to call stopService() on it in order to stop the
 * recording process.
 */
public class RecordingService extends Service {

    /** The interface binder for this service */
    private IBinder mRecordingBinder;
    /** Indicates if a recording process is ongoing */
    private boolean isRecording = false;

    /** The listeners to the recording state */
    private ArrayList<IRecordingListener> recordingListeners = new ArrayList<IRecordingListener>();
    /** A fake recording thread */
    private Thread thread;

    /** The events the telephony listener will monitor */
    private final static int telephonyEvents = TelephonyListener.LISTEN_DATA_STATE;
    /** The TelephonyServiceConnection used to access the TelephonyService */
    private LocalServiceConnection<TelephonyService> telServiceConnection = new LocalServiceConnection<TelephonyService>(
            TelephonyService.class);
    /** The LocationServiceConnection used to access the LocationService */
    private LocalServiceConnection<LocationService> locServiceConnection = new LocalServiceConnection<LocationService>(
            LocationService.class);

    /** The SQL connector */
    private SQLConnector connector;
    /** Indicate if the database has been opened successfully */
    private volatile boolean isDBavailable = false;
    /** The database context */
    private MeasureContext databaseContext;
    /** The sampling rate in milliseconds */
    private int sampleRate;
    /** The list of the measures to record */
    private List<Integer> metrics;

    /** The sampling handler */
    private final Handler samplingHandler = new Handler();

    /** This runnable defines the action to do on a sampling event */
    public final Runnable samplingRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                // If we are still recording
                if (isRecording) {
                    // Refresh all the telephony data
                    sample(metrics);
                }
            } catch (Exception exc) {
                // Log the error
                Log.e("SamplingRunnable", "", exc);
            } finally {
                // If we are still recording
                if (isRecording) {
                    // Call again later
                    samplingHandler.postDelayed(this, sampleRate);
                } else {
                    // Close the database connector
                    connector.close();
                    // The database is no more available
                    isDBavailable = false;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        // Initialize a RecordingBinder that knows this Service
        mRecordingBinder = new LocalBinder<RecordingService>(this);

        // Get the sample rate preference
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        sampleRate = prefs.getInt(
                getString(R.string.pref_key_display_sampling_rate),
                getResources().getInteger(R.integer.default_sampling_rate))
                * MyConstants.MILLIS_IN_SECOND;
        // Get the metrics preferences TODO
        metrics = new ArrayList<Integer>(Arrays.asList(new Integer[] {
                1, 2, 3
        }));

        // The database is not yet available
        isDBavailable = false;
        // Initialize the data base
        try {
            // Initialize the SQL connector
            this.connector = new SQLConnector(this);

        } catch (DataBaseException exc) {
            Toast toast = Toast.makeText(getApplicationContext(),
                    "can't initialize SQLConnector : " + exc.toString(),
                    Toast.LENGTH_SHORT); // TODO string
            toast.show();
        }

        // If initialization succeed
        if (connector != null) {
            // Initialize the database context
            databaseContext = new MeasureContext();

            // Bind to the telephony and location services
            telServiceConnection.bindToService(this);
            locServiceConnection.bindToService(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Return our interface binder
        return mRecordingBinder;
    }

    /** Indicates whether the service is currently recording data */
    public boolean isRecording() {
        return isRecording;
    }

    // TODO
    /** Set the sampling rate to the specified value in milliseconds */
    public void setSamplingRate(int millis) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The database is available
        if (!isRecording) {
            // Open the database
            try {
                this.connector.open();// la bdd est gener�e � partir du cr�ateur
                isDBavailable = true;
            } catch (SQLException exc) {
                Toast toast = Toast.makeText(getApplicationContext(),
                        "can't open SQLConnector : " + exc.toString(),
                        Toast.LENGTH_SHORT); // TODO string
                toast.show();
            }

            if (!isDBavailable) {
                // TODO error string
                Toast toast = new Toast(this);
                toast.setText("Database non available");
                toast.show();
                // Send a message to stop this service immediatly
                stopSelf();
            } else {
                // Start the recording thread
                startRecording();
                // Ask for a short enough update rate of location
                locServiceConnection.getService().setMinimumRefreshRate(
                        sampleRate);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (isRecording) {
            // Stop the recording process
            stopRecording();
        }
        // Unbind from the TelephonyService if needed
        unbindService(telServiceConnection);
        // Unbind from the LocationService if needed
        unbindService(locServiceConnection);
        super.onDestroy();
    }

    /** Start the recording process. */
    private void startRecording() {
        // Update the recording state
        isRecording = true;
        // Create the notification that will be displayed
        Notification notification = NotificationCenter
                .getRecordingNotification(this);
        // Notify we are running in foreground
        startForeground(NotificationCenter.BACKGROUND_RECORDING, notification);

        // Start the sampling process
        samplingHandler.post(samplingRunnable);

        // Notify the listeners
        notifyRecording();
    }

    /** Stop the recording process */
    public void stopRecording() {
        // Update the recording state
        isRecording = false;
        // Notify the listeners
        notifyRecording();
        // Stop being foreground as we are no longer recording and remove
        // notification
        stopForeground(true);
        // Stop self
        stopSelf();
    }

    /** Add a recording listener */
    public void register(IRecordingListener listener) {
        // Add it to the list
        recordingListeners.add(listener);
        // Notify it immediatly
        listener.onRecordingChanged(isRecording);
    }

    /** Remove a recording listener */
    public void unregister(IRecordingListener listener) {
        // Remove it from the list
        recordingListeners.remove(listener);
    }

    /** Notify all the recording listener */
    private void notifyRecording() {
        for (IRecordingListener listener : recordingListeners) {
            // For each listener, notify
            listener.onRecordingChanged(isRecording);
        }
    }

    /**
     * Fetch the current telephony data, and make an insertion in the database
     */
    private void sample(List<Integer> fields) {
        Log.d("SamplingRunnable", "sample()");
        // Check that the services are available
        if (locServiceConnection.isAvailable()
                && telServiceConnection.isAvailable()) {
            // Get the services
            TelephonyService telService = telServiceConnection.getService();
            LocationService locService = locServiceConnection.getService();

            // Fetch the location
            Location location = locService.getLocation();

            long now = System.currentTimeMillis();
            long age = now - location.getTime();

            if (age > 2 * sampleRate) {
                // The data are too old
                Log.d("SamplingRunnable", "Too old : " + age);
                return;
            }

            // The primary cell
            ICellInfo primaryCell = null;
            // Get all the cell infos
            List<ICellInfo> cellInfos = telService.getAllCellInfo();
            // Find the registered cell
            for (ICellInfo cell : cellInfos) {
                // If primary cell
                if (cell.isRegistered()) {
                    primaryCell = cell;
                    break;
                }
            }

            if (primaryCell == null) {
                // We are not able to fetch the desired data
                return;
            }

            // Update the database context
            databaseContext.set(MeasureContext.GROUP_INDEX, MyConstants.group);
            databaseContext.set(MeasureContext.USER_INDEX, MyConstants.user);
            databaseContext.set(MeasureContext.MCC_INDEX, primaryCell.getMcc());
            databaseContext.set(MeasureContext.MNC_INDEX, primaryCell.getMnc());
            databaseContext.set(MeasureContext.NTC_INDEX,
                    telService.getNetworkType());

            // Fetch the telephony measures
            // Create the data hashmap
            HashMap<Integer, String> dataList = new HashMap<Integer, String>(
                    fields.size());

            // Fill the fields
            for (Integer field : fields) {
                String value = "";
                switch (field) {
                case MyConstants.FIELD_CALL_RESULT:
                    // Unimplemented
                    value = "unimplemented";
                    break;
                case MyConstants.FIELD_CELL_ID:
                    value += primaryCell.getCid();
                    break;
                case MyConstants.FIELD_SIGNAL_STRENGTH:
                    value += primaryCell.getSignalStrength().getDbm();
                    break;
                case MyConstants.FIELD_DOWNLOAD:
                    value = "unimplemented";
                    break;
                case MyConstants.FIELD_UPLOAD:
                    value = "unimplemented";
                    break;
                }
                // Insert in the database
                dataList.put(field, value);
            }

            // Create an AsyncTask for insertion and execute (we clone the
            // MeasureContext for thread separation)
            new SampleTask().execute(new SampleParam(databaseContext.clone(),
                    dataList, location.getLatitude(), location.getLongitude()));

        }
    }

    /** A class that encapsulate the parameters for the SampleTask */
    private class SampleParam {
        public MeasureContext measureContext;
        public HashMap<Integer, String> dataList;
        public double latitude;
        public double longitude;

        public SampleParam(MeasureContext context,
                HashMap<Integer, String> data, double lat, double longi) {
            this.measureContext = context;
            this.dataList = data;
            this.latitude = lat;
            this.longitude = longi;
        }
    }

    private class SampleTask extends AsyncTask<SampleParam, Void, Void> {
        @Override
        protected Void doInBackground(SampleParam... params) {
            // Get the passed parameters
            SampleParam parameters = params[0];
            // Insert the measure in the database
            try {
                connector.insertMeasure(parameters.measureContext,
                        parameters.dataList, parameters.latitude,
                        parameters.longitude);
                Log.d("SampleTask", "Insertion effectuée :\n"
                        + parameters.dataList.toString());
            } catch (DataBaseException e) {
                Log.e("SampleTask", "DataBaseException", e);
            } catch (CollectMeasureException e) {
                Log.e("SampleTask", "CollectMeasureException", e);
            }
            return null;
        }

    }

    /**
     * Convert the whole database to a custom CSV file and upload this file with
     * the prefered protocols
     */
    public void uploadDatabase() {
    		if(!isRecording){
    			//TODO : stop recording
   
	    	FileReadyListener writingCallback = new FileReadyListener() {
				@Override
				public void onFileReady(ByteArrayOutputStream file) {
					
						//TODO : make recording run again if it was running before calling uploadDatabase()
					
					 if(file==null){
					      	Toast toast = Toast.makeText(getApplicationContext(), "No leaf to be write ", Toast.LENGTH_SHORT);
							toast.show();
					 }else{
						 //Creation of a sending CallBack : called when one sending is done
						 SendCompleteListener sendingCallback = new SendCompleteListener(){
							@Override
							public void onTaskCompleted(String protocole,
									HashMap<String, FileToUpload> filesSended) {
								//when a sending is done, the call back triggers the others chosen
						    	if(){//if http sending is done
						    		if(){//if ftp sending is desired by user
						    			String url = "192.168.0.4";
						    			DataSendingManager managerFTP = new DataSendingManager(url,filesSended,/*tvFtp*/,"ftp",this);
						    			managerFTP.execute();
						    		}else if(cbMail.isChecked()){//if mail sending is desired by user
						    			String url = "";
						    			EmailFileSender.sendFileByEmail(/*get main Act context*/,url,filesSended);
						    		}	
						    	}
						    	else if(protocole.equals("ftp")){//Sif ftp sending is done
						    		if(cbMail.isChecked()){//check if mail sending is desired bu user
						    			String url = "";
						    			sendFileByEmail(url,filesSended);
						    		}	
						    	}								
							}							 
						 };
						 
						 //Prepartion of the hashmap that contain the file to be sent
						 HashMap<String,FileToUpload> filesToSend = new HashMap<String,FileToUpload>();
						 //generating file name with timestamp to preserve unicity
						 String name = "file"+System.currentTimeMillis();
						 //getting input stream from the ByteArrayOutputStream recieved
						 InputStream content = new ByteArrayInputStream(file.toByteArray());
						 //creating file object
						 FileToUpload monFichier = new FileToUpload(name,content);
						 //inserting file into hasmap referenced with a name;
						 filesToSend.put("uploadedFile", monFichier);
						
						 if(/*KNOW IF HTTP IS CHOSEN IN PREFEENCES*/){
							 //setting server URL : normaly feching if from constant Class
							 String url = "http://192.168.0.4:8080/upload";
							 //creation and execution of a DataSendingManager : printing widget has to be resolved
							 DataSendingManager managerHTTP = new DataSendingManager(url,filesToSend,/*tvHttp*/,"http",sendingCallback);
							 managerHTTP.execute();
						 }		
						 else if(/*KNOW IF FTP IS CHOSEN IN PREFEENCES*/){
							 //setting server URL : normaly feching if from constant Class
							 String url = "192.168.0.4";
							 //creation and execution of a DataSendingManager :  printing widget has to be resolved 
							 DataSendingManager managerFTP = new DataSendingManager(url,filesToSend,/*tvFtp*/,"ftp",sendingCallback);
							 managerFTP.execute();
						 }
						 else if(/*KNOW IF MAIL IS CHOSEN IN PREFEENCES*/){
							 //destination address has to be fetched from setting
							 String url = "";
							 EmailFileSender.sendFileByEmail(/*MainActivity context*/,url,filesToSend);
						 }
					 }
				}
			};
			String comments = "...comments about file...";
			FileGenerator writer = new FileGenerator(connector,comments,writingCallback);
    	}
    }
   }

