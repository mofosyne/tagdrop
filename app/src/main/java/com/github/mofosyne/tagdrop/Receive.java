package com.github.mofosyne.tagdrop;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.Arrays;

public class Receive extends AppCompatActivity {
    //

    //
    TextView debugDisp;

    // Related to structured append of seqences of QR codes
    boolean structuredAppendDetected;
    int currentSeqNum;
    int totalInSeqNum;
    String hashtype;
    String hashcheck;
    // Related to string based structured appends
    String datauriString; // Stores the full data uri string to parse
    // todo: Related to binary based string append.

    String[] contentArray; //  Stores sequences

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_Receive);

        // On load, clear everything
        clear();

        /*
        Intent iObj = getIntent();
        if ( iObj.getAction().equals("android.intent.action.VIEW") ) {
            datauriString = iObj.getDataString();
            String debugText =
                    "DEBUG VIEW\n"
                    + "Action: '" + iObj.getAction() + "'\n"
                    + ", getDataString:'" + iObj.getDataString() + "'\n"
                    + ", getData:'" + iObj.getData() + "'\n"
                    + ", getScheme:'" + iObj.getScheme() + "'\n"
                    + ", SCAN_RESULT:'" + iObj.getStringExtra("SCAN_RESULT") + "'\n"
                    + ", SCAN_RESULT_FORMAT:'" + iObj.getStringExtra("SCAN_RESULT_FORMAT") + "'\n"
                    + ", SCAN_RESULT:'" + iObj.getStringExtra("SCAN_RESULT") + "'\n"
                    + ", SCAN_RESULT:'" + iObj.getStringExtra("SCAN_RESULT") + "'\n"
                    ;
            debugDisp = (TextView) findViewById(R.id.debugView);
            debugDisp.setText( debugText );
            Log.d("incoming intent", debugText);

            //------ webview
            WebView myWebView = (WebView) findViewById(R.id.webdisp);
            WebSettings webSettings = myWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            //myWebView.loadData();
            myWebView.loadUrl(datauriString);
        } else {
            Log.d("incoming intent", "non detected");
        }
        */

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_readme) {
            startActivity( new Intent(this, ReadMe.class ));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    //----------------------------------------------------------------------

    public void launchContent(View v) { // You must have "View v" or app will crash
        openWebPage(datauriString);
    }

    // Does this work to parse dataUri??? I wonder... ANS: NOOOPE... this just replaces "data:" with "http:"
    public void openWebPage(String url) {
        Uri webpage = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }


    //--------------------------------------------------------------------------
    /*
    *   For zxing loading
    * */

    static int QR_READER_ACTIVITY_REQUEST_CODE=0;

    public void scanButton(View v) { // You must have "View v" or app will crash
        launchBarcodeReader("Scan first code. Press back/return to escape. ");
    }

    public void launchBarcodeReader( String promptMessage ) {
        /*
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
        intent.putExtra("SCAN_FORMATS", "DATA_MATRIX,QR_CODE,MAXICODE,PDF_417,AZTEC");
        intent.putExtra("PROMPT_MESSAGE", promptMessage );
        startActivityForResult(intent, QR_READER_ACTIVITY_REQUEST_CODE);
        */
        // Lifted from QRStream :D
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.addExtra("RESULT_DISPLAY_DURATION_MS", Long.valueOf(sharedPref.getString("scan_delay", "0"))); // Want to be instant
        integrator.addExtra("PROMPT_MESSAGE", promptMessage);
        integrator.addExtra("CHARACTER_SET", "ISO-8859-1"); // By default process as 8bit

        AlertDialog barcodeScannerPrompt
                = integrator.initiateScan(Arrays.asList("QR_CODE", "AZTEC", "DATA_MATRIX", "MAXICODE" ));
        if (barcodeScannerPrompt != null) {
            // If zxing not installed, then launch the install recommendation dialog in barcodeScannerPrompt.
            barcodeScannerPrompt.setButton(DialogInterface.BUTTON_NEGATIVE, null, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    setResult(RESULT_CANCELED, getIntent());
                    finish();
                }
            });
        }

    }

    public void clearButton(View v) { // You must have "View v" or app will crash
        clear();
    }



    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        /*
        *   ZXING integration
        *   parseActivityResult shows null, if reuqest code receifved is not the int from ZXING.
        * */
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if (result != null) {
            // handle scan result
            { // Add try catch here if needed (e.g. file io like in qrstream )
                if (result.getFormatName() != null) {
                    // Parse detected barcode
                    /*
                    *   Here we shall either do this in string mode or binary mode.
                    *   By default we are always in string mode. Unless we parse a manifest indicating that subsequent barcodes are binary content.
                    * */
                    /* debug
                    * */
                    String debugText = "DEBUG VIEW\n" + result.toString();
                    debugDisp = (TextView) findViewById(R.id.debugView);
                    debugDisp.setText( debugText );
                    Log.d("incoming intent", debugText);
                    /* Treat as string
                    * */
                    String contents = result.getContents();
                    // Save and append if required
                    if(structuredAppendDetected) {
                        //todo: logic on smart addition of qr content (Then we could auto launch when all is received.
                        contentArray[currentSeqNum] = contents;
                    } else {
                        if (false) {
                            //todo: would be good to autodetect datauris and respond as if it was a new sequence
                        } else {
                            // Dumb append mode
                            contentArray[currentSeqNum] = contents;
                            currentSeqNum++;
                            totalInSeqNum++;

                            // Ask for next sequence
                            launchBarcodeReader("Scanning Next Sequence. Press back/return to escape. ");
                        }
                    }
                    /* Or binary? */


                } else {
                    // No Barcode were detected.
                }
            }


        }

        // Update Preview Screen
        datauriString = TextUtils.join( "", contentArray ); // todo: need to concat all the arrays together
        debugDisp = (TextView) findViewById(R.id.debugView);
        debugDisp.setText( datauriString );
    }

    //-------------------------------------------------------
    public void clear(){
        structuredAppendDetected = false;
        currentSeqNum = 0;
        totalInSeqNum = 1;
        hashtype = "none";
        hashcheck = "";
        datauriString = "";
        contentArray = new String[50]; // For now, just hardcode the amount to 256, which is more than enough for anyone
        for(int i = 0; i < contentArray.length; i++){
            contentArray[i]="";
        }
        // Don't forget to reset the display
        debugDisp = (TextView) findViewById(R.id.debugView);
        debugDisp.setText( "" );
    }
}
