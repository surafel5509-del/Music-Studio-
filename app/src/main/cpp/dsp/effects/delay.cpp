#include "delay.h"
#include <cmath>
#include <cstring>
namespace wavestudio {
Delay::Delay() { params_={{"time","Time",20,2000,400,400,"ms"},{"feedback","Feedback",0,90,30,30,"%"},{"mix","Mix",0,100,35,35,"%"}}; }
void Delay::set_sample_rate(float sr){ Effect::set_sample_rate(sr); dl_[0].ensure((int)(sr*2.5)); dl_[1].ensure((int)(sr*2.5)); }
void Delay::reset(){ dl_[0]=Dline(); dl_[1]=Dline(); set_sample_rate(sample_rate_); }
void Delay::process(float** b,int nf,int nc){
    float dly=params_[0].value*0.001f*sample_rate_, fb=params_[1].value/100.f*0.85f, wet=params_[2].value/100.f;
    for(int c=0;c<nc;++c){
        auto& d=dl_[c<2?c:0];
        for(int i=0;i<nf;++i){
            int sz=d.buf.empty()?1:(int)d.buf.size();
            int off=(int)dly; float frac=dly-off;
            int i1=(d.idx-1-off+sz*2)%sz, i2=(d.idx-2-off+sz*2)%sz;
            float delayed=d.buf[i1]*(1-frac)+d.buf[i2]*frac;
            float outs=b[c][i]+delayed*fb;
            if(!d.buf.empty()){d.buf[d.idx]=outs; d.idx=(d.idx+1)%sz;}
            b[c][i]=b[c][i]*(1-wet)+delayed*wet;
        }
    }
}
}
