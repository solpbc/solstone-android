// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONObject
import java.time.*
import kotlin.math.*

/** Decorative sky. It only reads the clock and the bundled time-zone reference points. */
class SunArcView(context: Context) : View(context) {
    var ambient = false
        set(value) { field = value; scheduleMinuteTick(); invalidate() }
    var lowBitAmbient = false
    var burnInProtection = false
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zones by lazy { JSONObject(context.assets.open("sun-zone-points.json").bufferedReader().use { it.readText() }) }
    private var scene: SkyScene? = null
    private val minuteTick = object : Runnable {
        override fun run() { invalidate(); scheduleMinuteTick() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun scheduleMinuteTick() {
        removeCallbacks(minuteTick)
        // AmbientLifecycleObserver supplies the ambient ticks. Hidden views have
        // no redraw timer; the next visible frame reads the current clock.
        if (isAttachedToWindow && windowVisibility == VISIBLE && !ambient) {
            postDelayed(minuteTick, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleMinuteTick() }
    override fun onDetachedFromWindow() { removeCallbacks(minuteTick); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        scheduleMinuteTick()
        if (visibility == VISIBLE) invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width / d; val h = height / d
        canvas.drawColor(Color.BLACK)
        canvas.save(); canvas.scale(d, d)
        ink.isAntiAlias = !ambient || !lowBitAmbient
        if (ambient) {
            if (burnInProtection) {
                val shift = (java.time.LocalTime.now().minute % 3 - 1) * 2f
                canvas.translate(shift, -shift)
            }
            ink.color = Color.WHITE; ink.alpha = if (lowBitAmbient) 255 else 210; ink.textAlign = Paint.Align.CENTER
            ink.textSize = 28f * resources.configuration.fontScale
            canvas.drawText(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date()), w / 2, h * .46f, ink)
            val mono = if (lowBitAmbient) Color.WHITE else 0xffaaaaaa.toInt()
            sun(canvas, w / 2, h * .66f, 12f, mono, mono, 1f)
        } else {
            val current = scene?.takeIf { it.w == w && it.h == h } ?: SkyScene(w, h).also { scene = it }
            val now = ZonedDateTime.now()
            val coordinates = zones.optJSONArray(now.zone.id)
            val pair = if (coordinates != null) SunMath.solarPair(now, coordinates.getDouble(0), coordinates.getDouble(1)) else Pair(390.0,1170.0)
            val frame = SunMath.frame(now.hour * 60.0 + now.minute, pair.first, pair.second)
            val p = current.point(frame.t.coerceIn(0.0,1.0))
            if (frame.night < 1 && frame.t > -.02 && frame.t < 1.02) glow(canvas,p,current.radius * PHI,.22 * frame.envelope * (1-frame.night),0xffffcc33.toInt())
            if (frame.weight > .001) glow(canvas,current.point(frame.extended),current.radius*PHI*PHI,.62*frame.weight,mixGoldOrange(.3+.5*frame.twilight))
            current.drawNight(canvas, frame, ink)
            if (frame.t > -.02 && frame.t < 1.02) sun(canvas,p.x,p.y,current.radius.toFloat(),0xffffcc33.toInt(),0xffe8913a.toInt(),(.2*frame.envelope*(1-frame.night)).toFloat())
        }
        canvas.restore()
    }
    private fun mixGoldOrange(f: Double): Int {
        val t=f.coerceIn(0.0,1.0)
        fun lab(color:Int):DoubleArray {
            fun linear(v:Int):Double {val c=v/255.0;return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
            val r=linear(Color.red(color));val g=linear(Color.green(color));val b=linear(Color.blue(color))
            val l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b)
            val m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b)
            val s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b)
            return doubleArrayOf(.2104542553*l+.7936177850*m-.0040720468*s,1.9779984951*l-2.4285922050*m+.4505937099*s,.0259040371*l+.7827717662*m-.8086757660*s)
        }
        val a=lab(0xffffcc33.toInt());val b=lab(0xffe8913a.toInt());val c=DoubleArray(3){a[it]+(b[it]-a[it])*t}
        val l=(c[0]+.3963377774*c[1]+.2158037573*c[2]).pow(3)
        val m=(c[0]-.1055613458*c[1]-.0638541728*c[2]).pow(3)
        val s=(c[0]-.0894841775*c[1]-1.2914855480*c[2]).pow(3)
        fun channel(v:Double)=((if(v<=.0031308)v*12.92 else 1.055*max(0.0,v).pow(1/2.4)-.055)*255).roundToInt().coerceIn(0,255)
        return Color.rgb(channel(4.0767416621*l-3.3077115913*m+.2309699292*s),channel(-1.2684380046*l+2.6097574011*m-.3413193965*s),channel(-.0041960863*l-.7034186147*m+1.7076147010*s))
    }
    private fun glow(canvas: Canvas,p: PointF,r: Double,a: Double,color: Int) {
        ink.shader = RadialGradient(p.x,p.y,r.toFloat(),intArrayOf(alpha(color,a),alpha(color,a*.45),Color.TRANSPARENT),floatArrayOf(0f,.38f,1f),Shader.TileMode.CLAMP)
        ink.alpha=255; ink.style=Paint.Style.FILL
        canvas.drawCircle(p.x,p.y,r.toFloat(),ink); ink.shader=null
    }
    private fun sun(canvas: Canvas,x: Float,y: Float,r: Float,gold: Int,orange: Int,a: Float) {
        canvas.save(); canvas.translate(x,y); canvas.scale(r/13.5f,r/13.5f)
        ink.style=Paint.Style.FILL; ink.color=gold; ink.alpha=(a.coerceIn(0f,1f)*255).roundToInt()
        val base=8.5+1/PHI; val half=Math.toRadians(16.5)
        val bx=base*sin(half); val by=-base*cos(half)
        fun control(xb: Double,yb: Double): PointF {
            val mx=xb/2; val my=(-13.5+yb)/2
            val vx=xb; val vy=yb+13.5; val length=hypot(vx,vy)
            var px=-vy/length; var py=vx/length
            if(abs(mx+.27*px)>abs(mx-.27*px)){ px=-px; py=-py }
            return PointF((mx+.27*px).toFloat(),(my+.27*py).toFloat())
        }
        val right=control(bx,by); val left=control(-bx,by)
        val path=Path().apply {
            moveTo(0f,-13.5f); quadTo(right.x,right.y,bx.toFloat(),by.toFloat())
            arcTo(RectF(-base.toFloat(),-base.toFloat(),base.toFloat(),base.toFloat()),-73.5f,-33f)
            quadTo(left.x,left.y,0f,-13.5f); close()
        }
        repeat(10){ canvas.drawPath(path,ink); canvas.rotate(36f) }
        ink.color=orange; ink.alpha=(a.coerceIn(0f,1f)*255).roundToInt(); ink.style=Paint.Style.STROKE; ink.strokeWidth=(2/PHI+.5).toFloat()
        canvas.drawCircle(0f,0f,6.5f,ink); ink.style=Paint.Style.FILL; ink.alpha=255
        canvas.restore()
    }
}
private const val PHI=1.6180339887
private fun alpha(color: Int,a: Double)=((a.coerceIn(0.0,1.0)*255).roundToInt() shl 24) or (color and 0xffffff)
internal data class SkyFrame(val t: Double,val night: Double,val envelope: Double,val weight: Double,val extended: Double,val twilight: Double,val minute: Double,val rise: Double,val set: Double)
internal object SunMath {
    private fun wrap(x: Double,n: Double)=((x%n)+n)%n
    fun solarPair(now: ZonedDateTime,lat: Double,lon: Double): Pair<Double,Double> {
        repeat(184) { days ->
            val probe=now.minusDays(days.toLong())
            solar(probe.dayOfYear.toDouble(),probe.offset.totalSeconds/60.0,lat,lon)?.let { return it }
        }
        return Pair(390.0,1170.0)
    }
    private fun solar(n: Double,offset: Double,lat: Double,lon: Double): Pair<Double,Double>? {
        fun crossing(rise: Boolean): Double? {
            val t=n+((if(rise)6.0 else 18.0)-lon/15)/24
            val m=.9856*t-3.289; val mr=Math.toRadians(m)
            val l=wrap(m+1.916*sin(mr)+.020*sin(2*mr)+282.634,360.0); val lr=Math.toRadians(l)
            var ra=wrap(Math.toDegrees(atan(.91764*tan(lr))),360.0)
            ra=(ra+floor(l/90)*90-floor(ra/90)*90)/15
            val sd=.39782*sin(lr); val cd=cos(asin(sd)); val latitude=Math.toRadians(lat)
            val ch=(cos(Math.toRadians(90.833))-sd*sin(latitude))/(cd*cos(latitude))
            if(ch !in -1.0..1.0)return null
            val ha=(if(rise)360-Math.toDegrees(acos(ch)) else Math.toDegrees(acos(ch)))/15
            return wrap(wrap(ha+ra-.06571*t-6.622-lon/15,24.0)*60+offset,1440.0)
        }
        val rise=crossing(true)?:return null; var set=crossing(false)?:return null
        if(set<rise)set+=1440
        return Pair(rise,set)
    }
    fun frame(minute: Double,rise: Double,rawSet: Double): SkyFrame {
        val set=if(rawSet<rise)rawSet+1440 else rawSet; val dawn=rise-30; val dusk=set+30
        val m=when { dusk>1440&&minute<dusk-1440 -> minute+1440; dawn<0&&minute>=dawn+1440 -> minute-1440; else -> minute }
        val t=(m-dawn)/(dusk-dawn)
        val night=when { m<dawn->1.0; m<rise->1-(m-dawn)/30; m<=set->0.0; m<dusk->(m-set)/30; else->1.0 }
        val u=t.coerceIn(0.0,1.0); val envelope=when {u<.22->sin(u/.22*PI/2);u>.78->sin((1-u)/.22*PI/2);else->1.0}
        var weight=1-envelope; var extended=t; var twilight=0.0
        if(m<dawn||m>dusk){
            val span=wrap(dawn-dusk,1440.0); val ms=wrap(m-dusk,1440.0); val mid=span/2; val half=max(0.0,min(180.0,span-120))/2
            val from=mid-half; val to=mid+half
            when {
                ms<=from&&from>0 -> {twilight=ms/from; weight=(1-twilight*twilight).coerceAtLeast(0.0).pow(1.5); extended=1+.1*twilight}
                ms>=to&&span>to -> {twilight=(span-ms)/(span-to);weight=(1-twilight*twilight).coerceAtLeast(0.0).pow(1.5);extended=-.1*twilight}
                else -> {weight=0.0;extended=if(ms<mid)1.1 else -.1;twilight=1.0}
            }
        }
        return SkyFrame(t,night,envelope,weight,extended,twilight,m,rise,set)
    }
}
private class SkyScene(val w: Float,val h: Float) {
    val radius=PHI*min(w,h)/2
    private val d=radius/sqrt(2.0); private val dx=w+2*d; private val dy=h+2*d; private val chord=hypot(dx,dy)
    private val sag=tan(PI/20)/2*chord; private val arc=chord*chord/(8*sag)+sag/2
    private val ox=w/2-dy/chord*(arc-sag);private val oy=h/2+dx/chord*(arc-sag)
    private val a=atan2(-d-oy,-d-ox)
    private val sweep=atan2(h+d-oy,w+d-ox).let { b->var delta=b-a;while(delta>PI)delta-=2*PI;while(delta< -PI)delta+=2*PI;delta }
    private data class Star(val x: Double,val y: Double,val level: Int)
    private val stars: List<Star>
    init {
        var lo=Double.POSITIVE_INFINITY;var hi=0.0;var ta=Double.POSITIVE_INFINITY;var tb=Double.NEGATIVE_INFINITY
        for(i in 0..400){val f=i/100.0;val x=when{f<1->f*w;f<2->w.toDouble();f<3->(3-f)*w;else->0.0};val y=when{f<1->0.0;f<2->(f-1)*h;f<3->h.toDouble();else->(4-f)*h};val r=hypot(x-ox,y-oy);var t=atan2(y-oy,x-ox);while(t-a>PI)t-=2*PI;while(t-a< -PI)t+=2*PI;lo=min(lo,r);hi=max(hi,r);ta=min(ta,t);tb=max(tb,t)}
        ta-=abs(sweep)/2+.01;tb+=abs(sweep)/2+.01;lo=max(0.0,lo-4);hi+=4
        val count=(.5*(hi*hi-lo*lo)*(tb-ta)/9000).roundToInt()
        var seed=(w*7919+h*104729+124).toLong().toInt()
        fun next(): Double {seed+=0x6D2B79F5;var t=(seed xor(seed ushr 15))*(1 or seed);t=(t+((t xor(t ushr 7))*(61 or t))) xor t;return ((t xor(t ushr 14)).toLong() and 0xffffffffL)/4294967296.0}
        val result=mutableListOf<Star>()
        fun push(r:Double,t:Double,level:Int){result+=Star(ox+r*cos(t),oy+r*sin(t),level);repeat(4){next()}}
        repeat(count){val r=sqrt(lo*lo+next()*(hi*hi-lo*lo));val t=ta+next()*(tb-ta);val v=next();push(r,t,when{v<.07->0;v<.23->1;v<.53->2;else->3})}
        repeat((count*2.6).roundToInt()){val gs=sqrt(-2*ln(next()+1e-9))*cos(6.283*next());push(arc+gs*min(w,h)/PHI.pow(4.0),ta+next()*(tb-ta),if(next()<.12)2 else 3)}
        stars=result
    }
    fun point(t:Double)=PointF((ox+arc*cos(a+sweep*t)).toFloat(),(oy+arc*sin(a+sweep*t)).toFloat())
    private fun smooth(x:Double):Double {val v=x.coerceIn(0.0,1.0);return v*v*(3-2*v)}
    fun drawNight(canvas:Canvas,f:SkyFrame,paint:Paint){
        val dusk=f.set+30;val dawn=f.rise-30;val span=((dawn-dusk)%1440+1440)%1440
        val elapsed=((f.minute-dusk)%1440+1440)%1440
        val u=if(f.night<=0)if(f.t<.5)0.0 else 1.0 else if(elapsed<=span)elapsed/span else if(f.t<0)0.0 else 1.0
        val angle=sweep*(u-.5)
        val half=max(0.0,min(180.0,span-120))/2
        val midnight=((dusk+span/2)%1440+1440)%1440
        val relative=((f.minute-(midnight-half))%1440+1440)%1440
        val aurora=if(half>0&&relative<2*half)smooth(min(relative,2*half-relative)/min(45.0,half)) else 0.0
        if(aurora>.001) drawAurora(canvas,f.minute,aurora,paint)
        val visibility=(f.night*(1-.62*f.weight)).coerceIn(0.0,1.0)
        fun rotated(x:Double,y:Double)=PointF((ox+(x-ox)*cos(angle)-(y-oy)*sin(angle)).toFloat(),(oy+(x-ox)*sin(angle)+(y-oy)*cos(angle)).toFloat())
        fun suppression(p:PointF):Double {
            fun glow(q:PointF,r:Double,a:Double):Double {val v=hypot((p.x-q.x).toDouble(),(p.y-q.y).toDouble())/r;return if(v>=1)0.0 else a*if(v<=.38)1-.55*v/.38 else .45*(1-(v-.38)/.62)}
            val day=if(f.night<1&&f.t>-.02&&f.t<1.02)glow(point(f.t.coerceIn(0.0,1.0)),radius*PHI,.22*f.envelope*(1-f.night)) else 0.0
            val twilight=if(f.weight>.001)glow(point(f.extended),radius*PHI*PHI,.62*f.weight) else 0.0
            return 1-min(.9,1.9*(day+twilight))
        }
        fun dot(p:PointF,r:Double,opacity:Double){if(opacity<.01)return;paint.color=alpha(0xffffcc33.toInt(),opacity);paint.style=Paint.Style.FILL;canvas.drawCircle(p.x,p.y,r.toFloat(),paint)}
        for(star in stars){val p=rotated(star.x,star.y);if(p.x< -3||p.y< -3||p.x>w+3||p.y>h+3)continue;val level=star.level.toDouble();val opacity=.92*PHI.pow(-.75*level)*smooth((visibility-doubleArrayOf(.18,.38,.58,.74)[star.level])/.2);dot(p,1.75*PHI.pow(-.5*level),opacity*suppression(p))}
        val centre=point(.5);val cv=smooth((visibility-.3)/.35);val levels=intArrayOf(0,1,0,0,1,0,0,1,0,1)
        repeat(10){k->val q=Math.toRadians((-90+36*k).toDouble());val level=levels[k].toDouble();val p=rotated(centre.x+radius/PHI*cos(q),centre.y+radius/PHI*sin(q));val opacity=min(1.0,.92*PHI.pow(-.75*level)*1.25)*cv*suppression(p);val r=(1.75*PHI.pow(-.5*level)*1.35+.4)*.8;dot(p,r*4.2,opacity*.14);dot(p,r,opacity)}
        val meteorMinute=((f.minute-midnight)%1440+1440)%1440
        if(meteorMinute<10&&visibility>.2){
            var seed=1021
            fun next():Double {seed+=0x6D2B79F5;var t=(seed xor(seed ushr 15))*(1 or seed);t=(t+((t xor(t ushr 7))*(61 or t))) xor t;return ((t xor(t ushr 14)).toLong() and 0xffffffffL)/4294967296.0}
            val c=rotated(centre.x.toDouble(),centre.y.toDouble())
            repeat(11){i->
                val start=(i+.15+next()*.7)*54.5;next()
                val beam=floor(next()*10);val offset=1.05+next()*1.1;val length=min(w,h)*(.16+next()*.14);next();val brightness=.65+next()*.35
                if(meteorMinute*60+59>=start){
                    val q=Math.toRadians(-90+36*beam);val r=radius*6.5/13.5*(.25+.4*(offset-1.05));val x=(c.x+r*cos(q)).toFloat();val y=(c.y+r*sin(q)).toFloat()
                    val ex=(x+cos(q)*length).toFloat();val ey=(y+sin(q)*length).toFloat()
                    paint.shader=LinearGradient(x,y,ex,ey,intArrayOf(Color.TRANSPARENT,alpha(0xfffcf3e4.toInt(),.95*.42*brightness)),null,Shader.TileMode.CLAMP)
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=.9f;paint.strokeCap=Paint.Cap.ROUND
                    canvas.drawLine(x,y,ex,ey,paint);paint.shader=null;paint.style=Paint.Style.FILL
                }
            }
        }
        paint.alpha=255
    }
    private var auroraMinute=Double.NaN
    private var auroraImage:Bitmap?=null
    private fun drawAurora(canvas:Canvas,minute:Double,strength:Double,paint:Paint){
        if(auroraMinute!=floor(minute)){
            auroraMinute=floor(minute)
            val bw=ceil(w/4).toInt();val bh=ceil(h/4).toInt();val image=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);val small=Canvas(image);val phase=floor(minute)*.9
            fun hash(a:Double,b:Double):Double {val v=sin(a*12.9898+b*78.233)*43758.5453;return v-floor(v)}
            repeat(bw){i->
                val x=i*4+2.0;val dx=x-ox;val yb=oy-sqrt(max(0.0,arc*arc-dx*dx))+min(w,h)*.04*sin(x/min(w,h)*6+phase*.8)
                val fold=.5+.5*sin(x/min(w,h)*9+phase)*sin(x/min(w,h)*3.3-phase*.6)
                val ray=hash(i.toDouble(),floor(phase*3))*.5+hash(i+1.0,floor(phase*3))*.5
                val intensity=.34*strength*(.3+.7*fold*fold)*(.55+.45*sin(x/min(w,h)*27+phase*2.3))*(.45+.75*ray)
                if(intensity>=.004){
                    val y0=(yb/4+2).toFloat();val y1=((yb-min(w,h)*.42*(.7+.3*fold))/4).toFloat()
                    paint.shader=LinearGradient(0f,y0,0f,y1,intArrayOf(0x006cf0a8,alpha(0xff6cf0a8.toInt(),intensity),alpha(0xff6cf0a8.toInt(),intensity*.55),alpha(0xffa27cff.toInt(),intensity*.3),0x00a27cff),floatArrayOf(0f,.06f,.35f,.7f,1f),Shader.TileMode.CLAMP)
                    small.drawRect(i.toFloat(),min(y0,y1),i+1.2f,max(y0,y1),paint);paint.shader=null
                }
            }
            auroraImage=image
        }
        auroraImage?.let{bitmap->
            paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD);paint.alpha=28;paint.isFilterBitmap=true
            for(dx in intArrayOf(-2,0,2))for(dy in intArrayOf(-2,0,2))canvas.drawBitmap(bitmap,null,RectF(dx.toFloat(),dy.toFloat(),w+dx,h+dy),paint)
            paint.xfermode=null;paint.alpha=255
        }
    }
}
