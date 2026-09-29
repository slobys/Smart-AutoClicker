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
        boolean occluded = false;
        final boolean joystick = getIntent().getBooleanExtra("joystick", false);
        final Bitmap terrain = createTerrain();
        long downAt;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MapView() { super(RouteMapActivity.this); }
        Bitmap createTerrain() {
            Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); c.drawColor(Color.rgb(20,20,20));
            Paint road = new Paint(); road.setStyle(Paint.Style.STROKE); road.setStrokeWidth(2);
            java.util.Random random = new java.util.Random(32);
            for (int i=0;i<460;i++) {
                int rx=2+random.nextInt(465), ry=2+random.nextInt(465), color=80+random.nextInt(160);
                road.setColor(Color.rgb(color,color,color));
                c.drawRect(rx,ry,rx+5+random.nextInt(32),ry+5+random.nextInt(32),road);
            }
            return b;
        }
        Bitmap minimapFrame(int mapX, int mapY) {
            Bitmap frame = Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(frame);
            c.drawBitmap(terrain,new Rect(mapX-30,mapY-30,mapX+162,mapY+162),new Rect(0,0,192,192),null);
            Paint player = new Paint(); player.setColor(Color.WHITE); c.drawCircle(96,96,7,player);
            return frame;
        }
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
            if (!occluded) {
                canvas.drawBitmap(terrain, new Rect(x-30,y-30,x+162,y+162),new Rect(500,30,692,222), null);
                paint.setColor((System.currentTimeMillis()/300)%2==0?Color.CYAN:Color.WHITE);
                canvas.drawCircle(596,126,7,paint);
            }
            label(canvas, "Tap ground to move. Bottom-left: reset. Bottom-right: change map.", 50, getHeight() - 45);
            paint.setColor(Color.CYAN); canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 16, paint);
            paint.setColor(Color.DKGRAY); canvas.drawLine(getWidth()/2f - 150, getHeight()/2f, getWidth()/2f+150, getHeight()/2f, paint);
            label(canvas, joystick ? "Fixed joystick (hold)" : "Ground taps", getWidth()/2-260, getHeight()/2+180);
            postInvalidateDelayed(300); // Animated minimap marker supplies genuinely fresh projection frames.
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) downAt = event.getEventTime();
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (event.getY() > getHeight() - 100) {
                    if (event.getX() < getWidth()/3f) { x=100; y=100; moves=0; wrongMap=false; occluded=false; }
                    else if (event.getX() < getWidth()*2/3f) occluded = !occluded;
                    else wrongMap = !wrongMap;
                } else {
                    float dx=event.getX()-getWidth()/2f, dy=event.getY()-getHeight()/2f;
                    double radius=Math.hypot(dx,dy);
                    if (joystick && radius>=50 && radius<=150) {
                        double travel=(event.getEventTime()-downAt)/50.0;
                        x+=Math.round(dx/radius*travel); y+=Math.round(dy/radius*travel);
                    } else if (!joystick) { x += Math.round(dx/10); y += Math.round(dy/10); }
                    x=Math.max(31,Math.min(349,x)); y=Math.max(31,Math.min(349,y));
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
                JSONObject dense = new JSONObject(route.toString());
                JSONArray densePoints = new JSONArray();
                for (int i = 0; i <= 10; i++) densePoints.put(new JSONArray().put(100 + i * 3).put(100));
                for (int i = 1; i <= 10; i++) densePoints.put(new JSONArray().put(130).put(100 + i * 3));
                dense.put("id","cccccccc-cccc-cccc-cccc-cccccccccccc").put("name","Dense L route (21 samples)").put("points",densePoints);
                try (FileOutputStream out = openFileOutput("route-dense.json",MODE_PRIVATE)) { out.write(dense.toString().getBytes("UTF-8")); }
                Bitmap mini = minimapFrame(100,100);
                int[] colors = new int[192*192]; byte[] gray = new byte[colors.length];
                mini.getPixels(colors,0,192,0,0,192,192); mini.recycle();
                for (int i=0;i<colors.length;i++) {
                    int rgb=colors[i]; gray[i]=(byte)((((rgb>>16)&255)*77+((rgb>>8)&255)*150+(rgb&255)*29)>>8);
                }
                route.put("version",2).put("id","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").put("name","Visual minimap L route")
                    .put("positionMode","MINIMAP").put("joystickDurationMs",500).put("control",joystick?"JOYSTICK":"GROUND_TAP")
                    .put("points",new JSONArray("[[50000,50000],[50010,50000],[50010,50010]]"))
                    .put("minimap",new JSONObject().put("area",new JSONArray("[500,30,692,222]"))
                        .put("markerRadius",16).put("tested",true).put("keyframes",new JSONArray().put(new JSONObject()
                            .put("position",new JSONArray("[50000,50000]")) .put("gray",Base64.encodeToString(gray,Base64.NO_WRAP)))));
                try(FileOutputStream out=openFileOutput("route-minimap.json",MODE_PRIVATE)) { out.write(route.toString().getBytes("UTF-8")); }
            } catch(Exception e) { throw new RuntimeException(e); }
        }
    }
}
