package com.github.mofosyne.tagdrop;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

public class main extends AppCompatActivity {
    TextView debugDisp;

    String datauriString;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        if (id == R.id.action_settings) {
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

        Uri.Builder httpDataUri = webpage.buildUpon();
        httpDataUri.scheme("http");
        webpage = httpDataUri.build();

        Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }
}
