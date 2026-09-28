package ua.navshlyah.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.*;
import java.util.*;

public final class RoadGraph {
    public int n, m;
    public int[] latE6, lonE6, from, to;
    public short[] weight;
    public byte[] roadClass;
    public int[] head, next;
    public double minLat=90,maxLat=-90,minLon=180,maxLon=-180;

    public static RoadGraph load(Context ctx) throws IOException {
        RoadGraph g=new RoadGraph();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(ctx.getAssets().open("roads.bin")))){
            byte[] magic=new byte[4]; in.readFully(magic);
            boolean v2=Arrays.equals(magic,new byte[]{'N','S','G','2'});
            boolean v1=Arrays.equals(magic,new byte[]{'N','S','G','1'});
            if(!v1 && !v2) throw new IOException("bad graph");
            g.n=Integer.reverseBytes(in.readInt());
            g.m=Integer.reverseBytes(in.readInt());
            g.latE6=new int[g.n]; g.lonE6=new int[g.n];
            for(int i=0;i<g.n;i++){
                g.latE6[i]=Integer.reverseBytes(in.readInt());
                g.lonE6[i]=Integer.reverseBytes(in.readInt());
                double la=g.latE6[i]/1e6, lo=g.lonE6[i]/1e6;
                g.minLat=Math.min(g.minLat,la); g.maxLat=Math.max(g.maxLat,la);
                g.minLon=Math.min(g.minLon,lo); g.maxLon=Math.max(g.maxLon,lo);
            }
            g.from=new int[g.m]; g.to=new int[g.m]; g.weight=new short[g.m]; g.roadClass=new byte[g.m];
            g.head=new int[g.n]; Arrays.fill(g.head,-1); g.next=new int[g.m];
            for(int i=0;i<g.m;i++){
                int a=Integer.reverseBytes(in.readInt());
                int b=Integer.reverseBytes(in.readInt());
                int lo=in.readUnsignedByte(), hi=in.readUnsignedByte();
                int w=lo|(hi<<8);
                g.from[i]=a; g.to[i]=b; g.weight[i]=(short)w; g.roadClass[i]=(byte)(v2?in.readUnsignedByte():7);
                g.next[i]=g.head[a]; g.head[a]=i;
            }
        }
        return g;
    }

    public int nearest(double lat,double lon){
        double best=Double.MAX_VALUE; int bi=-1;
        double c=Math.cos(Math.toRadians(lat));
        for(int i=0;i<n;i++){
            double dy=lat-latE6[i]/1e6, dx=(lon-lonE6[i]/1e6)*c;
            double d=dx*dx+dy*dy;
            if(d<best){best=d;bi=i;}
        }
        return bi;
    }

    private static final class Q implements Comparable<Q>{
        int v; float f; Q(int v,float f){this.v=v;this.f=f;}
        public int compareTo(Q o){return Float.compare(f,o.f);}
    }

    public int[] route(int s,int t){
        float[] d=new float[n]; Arrays.fill(d,Float.POSITIVE_INFINITY);
        int[] prev=new int[n]; Arrays.fill(prev,-1);
        boolean[] done=new boolean[n];
        PriorityQueue<Q> pq=new PriorityQueue<>();
        d[s]=0; pq.add(new Q(s,heuristic(s,t)));
        while(!pq.isEmpty()){
            Q q=pq.poll(); int v=q.v;
            if(done[v]) continue; done[v]=true;
            if(v==t) break;
            for(int e=head[v];e!=-1;e=next[e]){
                int u=to[e]; float nd=d[v]+Short.toUnsignedInt(weight[e]);
                if(nd<d[u]){
                    d[u]=nd; prev[u]=v;
                    pq.add(new Q(u,nd+heuristic(u,t)));
                }
            }
        }
        if(prev[t]==-1 && s!=t) return new int[0];
        int len=1; for(int v=t;v!=s;v=prev[v]){ if(v<0)return new int[0]; len++; }
        int[] path=new int[len]; int i=len-1;
        for(int v=t;;v=prev[v]){ path[i--]=v; if(v==s)break; }
        return path;
    }

    private float heuristic(int a,int b){
        return (float)distance(lat(a),lon(a),lat(b),lon(b));
    }
    public double lat(int i){return latE6[i]/1e6;}
    public double lon(int i){return lonE6[i]/1e6;}

    public static double distance(double la1,double lo1,double la2,double lo2){
        double p1=Math.toRadians(la1),p2=Math.toRadians(la2);
        double dp=Math.toRadians(la2-la1),dl=Math.toRadians(lo2-lo1);
        double h=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 6371000*2*Math.asin(Math.min(1,Math.sqrt(h)));
    }

    public Bitmap render(int size){
        Bitmap bmp=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);
        Canvas c=new Canvas(bmp); c.drawColor(Color.rgb(242,244,239));
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setStrokeCap(Paint.Cap.ROUND);
        for(int cls=7;cls>=0;cls--){
            switch(cls){
                case 0: p.setColor(Color.rgb(232,154,75)); p.setStrokeWidth(5.4f); break;
                case 1: p.setColor(Color.rgb(238,177,95)); p.setStrokeWidth(4.8f); break;
                case 2: p.setColor(Color.rgb(244,202,116)); p.setStrokeWidth(4.0f); break;
                case 3: p.setColor(Color.rgb(247,221,150)); p.setStrokeWidth(3.2f); break;
                case 4: p.setColor(Color.rgb(213,209,193)); p.setStrokeWidth(2.4f); break;
                case 5: p.setColor(Color.rgb(202,202,196)); p.setStrokeWidth(1.7f); break;
                case 6: p.setColor(Color.rgb(211,211,205)); p.setStrokeWidth(1.4f); break;
                default:p.setColor(Color.rgb(219,219,214)); p.setStrokeWidth(1.0f); break;
            }
            for(int e=0;e<m;e++){
                if(Byte.toUnsignedInt(roadClass[e])!=cls) continue;
                int a=from[e],b=to[e];
                float x1=(float)((lon(a)-minLon)/(maxLon-minLon)*size);
                float y1=(float)((maxLat-lat(a))/(maxLat-minLat)*size);
                float x2=(float)((lon(b)-minLon)/(maxLon-minLon)*size);
                float y2=(float)((maxLat-lat(b))/(maxLat-minLat)*size);
                c.drawLine(x1,y1,x2,y2,p);
            }
        }
        return bmp;
    }
}
