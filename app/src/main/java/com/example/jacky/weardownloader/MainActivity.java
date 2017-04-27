package com.example.jacky.weardownloader;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.BoxInsetLayout;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static java.lang.System.currentTimeMillis;

public class MainActivity extends WearableActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String DownloadUrl =
            "http://www.audiocheck.net/download.php?filename=Audio/audiocheck.net_hdsweep_1Hz_96000Hz_-3dBFS_30s.wav";

    private BoxInsetLayout mContainerView;
    private TextView mLastDownloadTimeTextView;
    private TextView mBandwidthTextView;
    private ProgressDialog mProgressDialog;

    private ConnectivityManager mConnectivityManager;
    private DownloadTask mDownloadTask;

    private long mLastDownloadTime, mStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        mContainerView = (BoxInsetLayout) findViewById(R.id.container);
        mLastDownloadTimeTextView = (TextView) findViewById(R.id.LastDownloadTimeText);
        mBandwidthTextView = (TextView) findViewById(R.id.BandwidthTextView);

        // Progress Dialog
        mProgressDialog = new ProgressDialog(MainActivity.this);
        mProgressDialog.setMessage("Downloading");
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setCancelable(true);

        final Button button = (Button) findViewById(R.id.DownloadButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mDownloadTask = new DownloadTask(MainActivity.this);
                mDownloadTask.execute(DownloadUrl);
                mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        Log.i(TAG, "cancelling download!");
                        mDownloadTask.cancel(true);
                    }
                });
            }
        });

        checkNetworkInfo();
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
        updateDisplay();
    }

    @Override
    public void onUpdateAmbient() {
        super.onUpdateAmbient();
        updateDisplay();
    }

    @Override
    public void onExitAmbient() {
        updateDisplay();
        super.onExitAmbient();
    }

    private void checkNetworkInfo() {
        mConnectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = mConnectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return;
        }

        final NetworkInfo activeNetworkInfo = mConnectivityManager.getActiveNetworkInfo();
        final int bandwidthkBps =
                mConnectivityManager.getNetworkCapabilities(activeNetwork).getLinkDownstreamBandwidthKbps() / 8;

        mBandwidthTextView.setText(activeNetworkInfo.getTypeName() + ": " + bandwidthkBps + "kBps");
        Log.i(TAG, activeNetworkInfo.toString());

        return;
    }

    private void updateDisplay() {
        if (isAmbient()) {
            mContainerView.setBackgroundColor(getResources().getColor(android.R.color.black));
            mLastDownloadTimeTextView.setTextColor(getResources().getColor(android.R.color.white));
        } else {
            mContainerView.setBackground(null);
            mLastDownloadTimeTextView.setTextColor(getResources().getColor(android.R.color.black));
        }
    }

    // Internal AsyncTask class to perform downloading on a separate thread.
    // It holds a wakelock while downloading is in progress.
    private class DownloadTask extends AsyncTask<String, Integer, String> {
        private Context context;
        private PowerManager.WakeLock mWakeLock;

        public DownloadTask(Context context) {
            this.context = context;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    getClass().getName());
            mWakeLock.acquire();
            mStartTime = System.currentTimeMillis();
            mProgressDialog.show();
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            super.onProgressUpdate(progress);

            final int percent = progress[0] * 100 / progress[1];
            mProgressDialog.setIndeterminate(false);
            mProgressDialog.setProgress(progress[0]);
            mProgressDialog.setMax(progress[1]);

        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            mWakeLock.release();
            mLastDownloadTimeTextView.setText("Last Download Cancelled");
        }

        @Override
        protected void onPostExecute(String result) {
            mWakeLock.release();
            mProgressDialog.dismiss();
            if (result != null) {
                Toast.makeText(context, "Download error: " + result, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "File downloaded", Toast.LENGTH_SHORT).show();
                mLastDownloadTime = System.currentTimeMillis() - mStartTime;
                final long totalTime = TimeUnit.MILLISECONDS.toSeconds(mLastDownloadTime);
                mLastDownloadTimeTextView.setText("Last Download Time: " + totalTime + "s");
            }
        }

        @Override
        protected String doInBackground(String... sUrl) {
            InputStream input = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(sUrl[0]);
                connection = (HttpURLConnection) url.openConnection();
                connection.connect();

                // Check connection is OK
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return "Server returned HTTP " + connection.getResponseCode()
                            + " " + connection.getResponseMessage();
                }

                input = connection.getInputStream();
                final long fileLength = connection.getContentLength();
                byte data[] = new byte[1024];
                long totalReadBytes = 0;
                int currentReadBytes = 0;

                while ((currentReadBytes = input.read(data)) != -1) {
                    if (isCancelled()) {
                        input.close();
                        return null;
                    }
                    totalReadBytes += currentReadBytes;
                    if (fileLength > 0) {
                        publishProgress((int)(totalReadBytes), (int)fileLength);
                    }
                }
            } catch (Exception e) {
                return e.toString();
            } finally {
                try {
                    if (input != null)
                        input.close();
                } catch (IOException e) {
                    return e.toString();
                }

                if (connection != null)
                    connection.disconnect();
            }
            return null;
        }


    }
}
