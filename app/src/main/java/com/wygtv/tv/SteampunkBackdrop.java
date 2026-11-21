package com.wygtv.tv;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

/** Steampunk artwork scaled to cover the screen, with optional scrims that keep text readable. */
public final class SteampunkBackdrop extends Drawable {
 private static final SparseArray<Bitmap> CACHE=new SparseArray<>();
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 private final Bitmap art;
 private final boolean homeScrim;

 public SteampunkBackdrop(Resources res,int drawableId,boolean homeScrim){
  Bitmap cached=CACHE.get(drawableId);
  if(cached==null){cached=BitmapFactory.decodeResource(res,drawableId);CACHE.put(drawableId,cached);}
  art=cached;this.homeScrim=homeScrim;
 }

 @Override public void draw(Canvas c){
  Rect b=getBounds();float w=b.width(),h=b.height();if(w<=0||h<=0)return;
  c.drawColor(0xff140B06);
  if(art!=null){
   float scale=Math.max(w/art.getWidth(),h/art.getHeight());float dw=art.getWidth()*scale,dh=art.getHeight()*scale;
   float left=b.left+(w-dw)/2f,top=b.top+(h-dh)/2f;
   paint.setShader(null);c.drawBitmap(art,null,new RectF(left,top,left+dw,top+dh),paint);
  }
  if(homeScrim){
   // Soft darkening behind the centre tiles and along the bottom plate so cream text stays legible.
   paint.setShader(new RadialGradient(b.centerX(),b.top+h*.52f,w*.42f,new int[]{0x8C120804,0x40120804,0x00120804},new float[]{0f,.6f,1f},Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(new LinearGradient(0,b.top+h*.78f,0,b.bottom,new int[]{0x00120804,0xB3120804},null,Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(new LinearGradient(0,b.top,0,b.top+h*.16f,new int[]{0x99120804,0x00120804},null,Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(null);
  }
 }

 @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
 @Override public void setColorFilter(ColorFilter cf){paint.setColorFilter(cf);invalidateSelf();}
 @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
