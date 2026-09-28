package ua.navshlyah.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.Toast;

public final class NavMapView extends View {
    public interface LongPressListener { void onPoint(double lat,double lon); }
    private RoadGraph g; private Bitmap base; private int[] route=new int[0];
    private double curLat=50.45,curLon=30.52;
    private float zoom=1f, offX=0,offY=0,lastX,lastY; private boolean drag=false;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gd;
    private final ScaleGestureDetector sd;
    private LongPressListener lp;

    public NavMapView(Context c){super(c);setBackgroundColor(Color.WHITE);
        gd=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public void onLongPress(MotionEvent e){
                if(g==null||base==null||lp==null)return;
                double[] ll=screenToLatLon(e.getX(),e.getY()); lp.onPoint(ll[0],ll[1]);
            }
            public boolean onDoubleTap(MotionEvent e){zoom=Math.min(8f,zoom*1.5f);invalidate();return true;}
        });
        sd=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScale(ScaleGestureDetector detector){
                float old=zoom;
                float next=Math.max(0.75f,Math.min(14f,zoom*detector.getScaleFactor()));
                float fx=detector.getFocusX(), fy=detector.getFocusY();
                if(old!=0f){
                    offX=fx-(fx-offX)*(next/old);
                    offY=fy-(fy-offY)*(next/old);
                }
                zoom=next;
                invalidate();
                return true;
            }
        });
    }
    public void setLongPressListener(LongPressListener l){lp=l;}
    public void setGraph(RoadGraph graph){g=graph;base=g.render(2048);centerOn(curLat,curLon);invalidate();}
    public void setRoute(int[] r){route=r==null?new int[0]:r;invalidate();}
    public void setPosition(double lat,double lon,boolean center){curLat=lat;curLon=lon;if(center)centerOn(lat,lon);invalidate();}

    private float mapX(double lon){return (float)((lon-g.minLon)/(g.maxLon-g.minLon)*base.getWidth());}
    private float mapY(double lat){return (float)((g.maxLat-lat)/(g.maxLat-g.minLat)*base.getHeight());}
    private void centerOn(double lat,double lon){
        if(g==null||base==null||getWidth()==0)return;
        offX=getWidth()/2f-mapX(lon)*zoom; offY=getHeight()/2f-mapY(lat)*zoom;
    }
    private double[] screenToLatLon(float sx,float sy){
        double mx=(sx-offX)/zoom,my=(sy-offY)/zoom;
        double lon=g.minLon+mx/base.getWidth()*(g.maxLon-g.minLon);
        double lat=g.maxLat-my/base.getHeight()*(g.maxLat-g.minLat);
        return new double[]{lat,lon};
    }

    protected void onDraw(Canvas c){
        super.onDraw(c); if(g==null||base==null)return;
        c.save(); c.translate(offX,offY); c.scale(zoom,zoom); c.drawBitmap(base,0,0,p);
        if(route.length>1){
            p.setColor(Color.rgb(0,87,183));p.setStrokeWidth(5f/zoom);p.setStyle(Paint.Style.STROKE);
            Path path=new Path();path.moveTo(mapX(g.lon(route[0])),mapY(g.lat(route[0])));
            for(int i=1;i<route.length;i++)path.lineTo(mapX(g.lon(route[i])),mapY(g.lat(route[i])));
            c.drawPath(path,p);
        }
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(255,215,0));
        c.drawCircle(mapX(curLon),mapY(curLat),10f/zoom,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3f/zoom);p.setColor(Color.rgb(0,87,183));
        c.drawCircle(mapX(curLon),mapY(curLat),10f/zoom,p);
        c.restore();
    }

    public void zoomBy(float factor){
        float old=zoom;
        float next=Math.max(0.75f,Math.min(14f,zoom*factor));
        float fx=getWidth()/2f, fy=getHeight()/2f;
        if(old!=0f){
            offX=fx-(fx-offX)*(next/old);
            offY=fy-(fy-offY)*(next/old);
        }
        zoom=next;
        invalidate();
    }

    public boolean onTouchEvent(MotionEvent e){
        sd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if(sd.isInProgress()) return true;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();drag=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(drag){offX+=e.getX()-lastX;offY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();invalidate();}return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:drag=false;return true;
        }
        return true;
    }
}
