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
                      "Action: '" + iObj.getAction() + "'"
                    + ", getDataString:'" + iObj.getDataString() + "'"
                    + ", getData:'" + iObj.getData() + "'"
                    + ", getScheme:'" + iObj.getScheme() + "'"
                    + "'";
            debugDisp = (TextView) findViewById(R.id.debugView);
            debugDisp.setText( debugText );
            Log.d("incoming intent", debugText);

            //------ webview
            /*
            WebView webView = (WebView) findViewById(R.id.webview);
            webView.getSettings().setJavaScriptEnabled(true);

            webView.setWebChromeClient(new WebChromeClient() {
                public void onProgressChanged(WebView view, int progress) {
                    activity.setTitle("Loading...");
                    activity.setProgress(progress * 100);

                    if (progress == 100)
                        activity.setTitle(R.string.app_name);
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    // Handle the error
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    view.loadUrl(url);
                    return true;
                }
            });
            */



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
