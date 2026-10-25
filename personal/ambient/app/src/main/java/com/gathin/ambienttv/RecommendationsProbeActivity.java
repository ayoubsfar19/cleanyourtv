package com.gathin.ambienttv;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Shell-only diagnostic exports package counts, never titles, history, intents or account information. */
public final class RecommendationsProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            StringBuilder out=new StringBuilder("Suggestions Android TV\n");
            try(Cursor c=getContentResolver().query(Uri.parse("content://com.gathin.ambienttv.recommendations/status"),null,null,null,null)) {
                if(c!=null){String[] columns=c.getColumnNames();while(c.moveToNext()){
                    for(int i=0;i<columns.length;i++)out.append(columns[i]).append('=').append(c.isNull(i)?"":c.getString(i)).append(i+1==columns.length?'\n':' ');
                }}
            }
            File dir=getExternalFilesDir(null);
            if(dir!=null)Files.write(new File(dir,"recommendation-status.txt").toPath(),out.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){android.util.Log.w("RecommendationsProbe","Diagnostic unavailable",e);}
        finish();
    }
}
