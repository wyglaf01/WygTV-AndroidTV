package com.wygtv.tv;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent artwork for the WygTV home screen. */
public final class BeowulfScene extends View {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 public BeowulfScene(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 private void polygon(Canvas c,int color,float... points){paint.setShader(null);paint.setColor(color);paint.setStyle(Paint.Style.FILL);Path p=new Path();p.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]);p.close();c.drawPath(p,paint);}
 private void line(Canvas c,int color,float width,float x1,float y1,float x2,float y2){paint.setShader(null);paint.setColor(color);paint.setStrokeWidth(width);c.drawLine(x1,y1,x2,y2,paint);}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Canvas c=canvas;c.save();c.scale(getWidth()/960f,getHeight()/540f);
  paint.setShader(new LinearGradient(0,0,0,540,new int[]{0xff091426,0xff24384A,0xff080F1B},null,Shader.TileMode.CLAMP));c.drawRect(0,0,960,540,paint);paint.setShader(null);
  paint.setColor(0xffC8C1A4);c.drawCircle(770,112,48,paint);paint.setColor(0xff142334);c.drawCircle(750,98,46,paint);
  paint.setColor(0xff9EA9AF);for(int i=0;i<36;i++){float x=(i*137+31)%960,y=(i*53+23)%210;c.drawCircle(x,y,i%5==0?1.3f:.65f,paint);}
  polygon(c,0xff263745,0,320,90,220,180,285,290,180,400,300,520,210,660,315,790,215,960,300,960,540,0,540);
  polygon(c,0xff172635,0,365,150,280,285,365,425,285,590,365,735,288,960,350,960,540,0,540);
  // The mead hall and its warm windows at the edge of the mist.
  polygon(c,0xff0B1420,650,360,842,360,842,435,650,435);
  polygon(c,0xff101A26,625,365,745,283,866,365);
  line(c,0xff6B6755,3,625,365,745,283);line(c,0xff6B6755,3,745,283,866,365);
  paint.setColor(0xffBA8140);c.drawRect(724,378,756,435,paint);for(int i=0;i<4;i++)c.drawRect(666+i*46,380,676+i*46,401,paint);
  // A dragon silhouette curls above the hall.
  Path dragon=new Path();dragon.moveTo(552,169);dragon.cubicTo(620,102,698,147,714,171);dragon.cubicTo(739,207,788,181,809,161);dragon.lineTo(835,155);dragon.lineTo(820,144);dragon.lineTo(829,129);dragon.lineTo(806,141);dragon.cubicTo(772,157,756,177,735,163);dragon.lineTo(756,107);dragon.lineTo(704,135);dragon.lineTo(665,91);dragon.lineTo(675,140);dragon.cubicTo(628,116,583,135,552,169);dragon.close();paint.setColor(0xff0A1421);c.drawPath(dragon,paint);
  // Foreground sword and round shields suggest Beowulf and Wiglaf.
  polygon(c,0xff0A111C,96,470,109,270,122,470);polygon(c,0xff78838B,106,445,109,275,115,445,110,463);
  line(c,0xffB59456,7,83,442,139,442);line(c,0xff8F7447,8,110,443,110,484);
  paint.setColor(0xff101B29);c.drawCircle(177,481,56,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);paint.setColor(0xff897247);c.drawCircle(177,481,54,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff9C8252);c.drawCircle(177,481,12,paint);
  paint.setShader(new LinearGradient(0,240,0,540,new int[]{0x00070D17,0xdd070D17},null,Shader.TileMode.CLAMP));c.drawRect(0,230,960,540,paint);
  paint.setShader(new RadialGradient(480,265,510,new int[]{0x17050B13,0x92050B13},null,Shader.TileMode.CLAMP));c.drawRect(0,0,960,540,paint);paint.setShader(null);c.restore();
 }
}
