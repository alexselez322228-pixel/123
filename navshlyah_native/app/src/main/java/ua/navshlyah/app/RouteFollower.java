package ua.navshlyah.app;

public final class RouteFollower {
    private final RoadGraph g;
    private int[] path=new int[0];
    private int seg=0;
    private double segOffset=0;

    public RouteFollower(RoadGraph g){this.g=g;}
    public void setPath(int[] p){path=p==null?new int[0]:p;seg=0;segOffset=0;}
    public boolean active(){return path.length>1 && seg<path.length-1;}

    public double[] advance(double meters){
        if(path.length==0) return null;
        while(meters>0 && seg<path.length-1){
            int a=path[seg], b=path[seg+1];
            double len=RoadGraph.distance(g.lat(a),g.lon(a),g.lat(b),g.lon(b));
            double remain=Math.max(0.01,len-segOffset);
            if(meters<remain){segOffset+=meters; meters=0;}
            else {meters-=remain; seg++; segOffset=0;}
        }
        if(seg>=path.length-1){
            int v=path[path.length-1]; return new double[]{g.lat(v),g.lon(v)};
        }
        int a=path[seg],b=path[seg+1];
        double len=RoadGraph.distance(g.lat(a),g.lon(a),g.lat(b),g.lon(b));
        double t=Math.max(0,Math.min(1,segOffset/Math.max(0.01,len)));
        return new double[]{g.lat(a)+(g.lat(b)-g.lat(a))*t,g.lon(a)+(g.lon(b)-g.lon(a))*t};
    }
}
