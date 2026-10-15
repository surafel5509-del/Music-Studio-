#include "chorus_flanger_phaser.h"
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
namespace wavestudio {
Chorus::Chorus(){params_={{"rate","Rate",0.1f,5,1.0f,1.0f,"Hz"},{"depth","Depth",1,20,8,8,"ms"},{"mix","Mix",0,100,50,50,"%"}};}
void Chorus::set_sample_rate(float sr){Effect::set_sample_rate(sr);d_[0].ensure((int)(sr*0.05));d_[1].ensure((int)(sr*0.05));}
void Chorus::reset(){*this=Chorus();}
void Chorus::process(float**b,int nf,int nc){
 float rate=params_[0].value,depth=params_[1].value*0.001f*sample_rate_,wet=params_[2].value/100.f;
 for(int c=0;c<nc;++c){auto&dl=d_[c<2?c:0];
  for(int i=0;i<nf;++i){
   dl.ph+=rate/sample_rate_*2*(float)M_PI;if(dl.ph>2*(float)M_PI)dl.ph-=2*(float)M_PI;
   float mod=depth*(0.5f+0.5f*sinf(dl.ph));
   int sz=dl.buf.empty()?1:(int)dl.buf.size(); int off=(int)mod; float frac=mod-off;
   int i1=(dl.idx-1-off+sz*2)%sz,i2=(dl.idx-2-off+sz*2)%sz;
   float delayed=dl.buf[i1]*(1-frac)+dl.buf[i2]*frac;
   dl.write(b[c][i]+delayed*0.3f);
   b[c][i]=b[c][i]*(1-wet)+delayed*wet;
  }}
}
Flanger::Flanger(){params_={{"rate","Rate",0.05f,4,0.5f,0.5f,"Hz"},{"depth","Depth",1,10,4,4,"ms"},{"fb","Feedback",0,90,40,40,"%"},{"mix","Mix",0,100,50,50,"%"}};}
void Flanger::set_sample_rate(float sr){Effect::set_sample_rate(sr);d_[0].ensure((int)(sr*0.02));d_[1].ensure((int)(sr*0.02));}
void Flanger::reset(){*this=Flanger();}
void Flanger::process(float**b,int nf,int nc){
 float rate=params_[0].value,depth=params_[1].value*0.001f*sample_rate_,fb=params_[2].value/100.f,wet=params_[3].value/100.f;
 for(int c=0;c<nc;++c){auto&dl=d_[c<2?c:0];
  for(int i=0;i<nf;++i){
   dl.ph+=rate/sample_rate_*2*(float)M_PI;if(dl.ph>2*(float)M_PI)dl.ph-=2*(float)M_PI;
   float mod=1+depth*(0.5f+0.5f*sinf(dl.ph));
   int sz=dl.buf.empty()?1:(int)dl.buf.size();int off=(int)mod;float frac=mod-off;
   int i1=(dl.idx-1-off+sz*2)%sz,i2=(dl.idx-2-off+sz*2)%sz;
   float delayed=dl.buf[i1]*(1-frac)+dl.buf[i2]*frac;
   float outs=b[c][i]+delayed*fb; dl.write(outs);
   b[c][i]=b[c][i]*(1-wet)+delayed*wet;
  }}
}
Phaser::Phaser(){params_={{"rate","Rate",0.05f,5,0.5f,0.5f,"Hz"},{"depth","Depth",100,3000,800,800,"Hz"},{"fb","Feedback",0,90,0,0,"%"}};}
void Phaser::set_sample_rate(float sr){Effect::set_sample_rate(sr);}
void Phaser::reset(){ph_=0;for(int c=0;c<2;++c)for(int n=0;n<6;++n)ap_[c][n]=AP();}
void Phaser::process(float**b,int nf,int nc){
 float rate=params_[0].value,depth=params_[1].value,fb=params_[2].value/100.f;
 for(int c=0;c<nc;++c){float lf=0;
  for(int i=0;i<nf;++i){
   ph_+=rate/sample_rate_*2*(float)M_PI;if(ph_>2*(float)M_PI)ph_-=2*(float)M_PI;
   float freq=depth*(0.7f+0.5f*sinf(ph_));
   float coef=(1.f-tanf((float)M_PI*freq/sample_rate_))/(1.f+tanf((float)M_PI*freq/sample_rate_));
   float in=b[c][i]+lf*fb,v=in; for(int n=0;n<6;++n)v=ap_[c<2?c:0][n].tick(v,coef); lf=v;
   b[c][i]=0.5f*(b[c][i]+v);
  }}
}
}
