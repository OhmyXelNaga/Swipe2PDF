package com.example.swipe2pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max

object ImageAndPdf {
    fun cropFile(source: File, crop: NormalizedRect): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(source.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && crop.valid())
        val decoder=BitmapRegionDecoder.newInstance(source.path,false)
        return decoder.decodeRegion(crop.pixels(bounds.outWidth,bounds.outHeight),BitmapFactory.Options()) ?: error("이미지를 자를 수 없습니다.")
    }
    fun similarity(a:File,b:File):Double {
        fun small(f:File)=BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply { inSampleSize=16 })
        val x=small(a)?:return 0.0; val y=small(b)?:run{x.recycle();return 0.0}
        val w=32;val h=32;val sx=Bitmap.createScaledBitmap(x,w,h,true);val sy=Bitmap.createScaledBitmap(y,w,h,true);x.recycle();y.recycle()
        var delta=0L
        for(i in 0 until w*h){val p=sx.getPixel(i%w,i/w);val q=sy.getPixel(i%w,i/w);delta+=kotlin.math.abs(Color.red(p)-Color.red(q))+kotlin.math.abs(Color.green(p)-Color.green(q))+kotlin.math.abs(Color.blue(p)-Color.blue(q))}
        sx.recycle();sy.recycle(); return 1.0-delta.toDouble()/(w*h*255*3)
    }
    fun uniqueName(existing:Set<String>,requested:String):String {
        val base=requested.trim().removeSuffix(".pdf").ifBlank{"스캔"};var name="$base.pdf";var n=2
        while(name in existing) name="$base ($n).pdf".also{n++};return name
    }
    fun createPdf(context:Context,files:List<File>,crop:NormalizedRect,requested:String):Pair<Uri,String> {
        require(files.isNotEmpty());val existing=mutableSetOf<String>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),"${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Swipe2PDF/"),null)?.use{c->while(c.moveToNext())existing+=c.getString(0)}
        val name=uniqueName(existing,requested);val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/Swipe2PDF/");put(MediaStore.MediaColumns.IS_PENDING,1)}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("PDF 저장 위치를 만들 수 없습니다.")
        try { PdfDocument().use { pdf -> files.forEachIndexed { index,file -> val bmp=cropFile(file,crop);val maxSide=2000f;val scale=minOf(1f,maxSide/max(bmp.width,bmp.height));val pw=max(1,(bmp.width*scale).toInt());val ph=max(1,(bmp.height*scale).toInt());val page=pdf.startPage(PdfDocument.PageInfo.Builder(pw,ph,index+1).create());page.canvas.drawBitmap(bmp,null,Rect(0,0,pw,ph),Paint(Paint.FILTER_BITMAP_FLAG));pdf.finishPage(page);bmp.recycle() }; context.contentResolver.openOutputStream(uri,"w")!!.use(pdf::writeTo) }; values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);context.contentResolver.update(uri,values,null,null);return uri to name }
        catch(t:Throwable){context.contentResolver.delete(uri,null,null);throw t}
    }
}
