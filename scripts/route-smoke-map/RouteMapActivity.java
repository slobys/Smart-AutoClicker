/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.klickr.routetest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.util.Base64;
import org.json.*;
import java.io.*;

/** Local test fixture only. Not part of the shipped application. No permissions, network, or game access. */
public class RouteMapActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        setContentView(new MapView());
    }
    final class MapView extends View {
        int x = 100, y = 100, moves = 0;
        boolean wrongMap = false;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MapView() { super(RouteMapActivity.this); }
        void label(Canvas c, String text, int px, int py) {
            paint.setColor(Color.WHITE); paint.setTextSize(44); paint.setTypeface(Typeface.MONOSPACE);
            c.drawText(text, px, py, paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(10, 30, 40));
            label(canvas, wrongMap ? "OTHER MAP" : "ROUTE MAP", 50, 85);
            label(canvas, Integer.toString(x), 50, 155);
            label(canvas, Integer.toString(y), 240, 155);
            label(canvas, "Moves: " + moves, 50, 230);
            label(canvas, "Tap ground to move. Bottom-left: reset. Bottom-right: change map.", 50, getHeight() - 45);
            paint.setColor(Color.CYAN); canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 16, paint);
            paint.setColor(Color.DKGRAY); canvas.drawLine(getWidth()/2f - 150, getHeight()/2f, getWidth()/2f+150, getHeight()/2f, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (event.getY() > getHeight() - 100) {
                    if (event.getX() < getWidth()/2f) { x=100; y=100; moves=0; wrongMap=false; }
                    else wrongMap = !wrongMap;
                } else {
                    x += Math.round((event.getX() - getWidth()/2f)/10);
                    y += Math.round((event.getY() - getHeight()/2f)/10);
                    moves++;
                }
                invalidate();
                android.util.Log.i("RouteTestMap", "position="+x+","+y+" moves="+moves+" wrongMap="+wrongMap);
                performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (w <= 0 || h <= 0) return;
            // A deterministic saved route lets UI smoke tests exercise the real OCR / gesture runtime.
            try {
                Bitmap marker = Bitmap.createBitmap(320, 75, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(marker); c.drawColor(Color.rgb(10, 30, 40)); label(c, "ROUTE MAP", 10, 55);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); marker.compress(Bitmap.CompressFormat.PNG,100,bytes); marker.recycle();
                JSONObject route = new JSONObject();
                route.put("version",1).put("id","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").put("name","Local test L route")
                    .put("width",w).put("height",h).put("x",new JSONArray("[40,110,185,175]"))
                    .put("y",new JSONArray("[230,110,385,175]")).put("map",new JSONArray("[40,30,360,105]"))
                    .put("mapPng",Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    .put("anchor",new JSONArray().put(w/2.0).put(h/2.0)).put("control","GROUND_TAP")
                    .put("tolerance",2).put("complete",true).put("points",new JSONArray("[[100,100],[110,100],[110,110]]"))
                    .put("calibration",new JSONArray("[[[100,0],[10,0]],[[0,100],[0,10]]]"));
                try (FileOutputStream out = openFileOutput("route.json",MODE_PRIVATE)) { out.write(route.toString().getBytes("UTF-8")); }
            } catch(Exception e) { throw new RuntimeException(e); }
        }
    }
}
