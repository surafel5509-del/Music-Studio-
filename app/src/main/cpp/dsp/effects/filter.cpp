#include "filter.h"
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
namespace wavestudio {
Filter::Filter(){params_={{"type","Type",0,3,0,0,""},{"freq","Cutoff",20,20000,1000,1000,"Hz"},{"q","Q",0.1f,10,1,1,""}};}
void Filter::set_sample_rate(float sr){Effect::set_sample_rate(sr);}
void Filter::reset(){for(int c=0;c<2;++c)bq_[c]=BQ();}
void Filter::design(float fc,float q,Type t){
 float w0=2*(float)M_PI*fc/sample_rate_,c=cosf(w0),s=sinf(w0),a=s/(2*q),a0=1+a;
 switch(t){
  case LP:b0=(1-c)/2/a0;b1=(1-c)/a0;b2=(1-c)/2/a0;a1=-2*c/a0;a2=(1-a)/a0;break;
  case HP:b0=(1+c)/2/a0;b1=-(1+c)/a0;b2=(1+c)/2/a0;a1=-2*c/a0;a2=(1-a)/a0;break;
  case BP:b0=a/a0;b1=0;b2=-a/a0;a1=-2*c/a0;a2=(1-a)/a0;break;
  case NOTCH:b0=1/a0;b1=-2*c/a0;b2=1/a0;a1=-2*c/a0;a2=(1-a)/a0;break;
 }
}
void Filter::process(float**b,int nf,int nc){
 float fc=params_[1].value,q=params_[2].value; Type t=(Type)(int)params_[0].value;
 design(fc,q,t);
 for(int c=0;c<nc;++c){auto& st=bq_[c<2?c:0];
  for(int i=0;i<nf;++i){float x=b[c][i];
   float y=b0*x+b1*st.x1+b2*st.x2-a1*st.y1-a2*st.y2;
   st.x2=st.x1;st.x1=x;st.y2=st.y1;st.y1=y; b[c][i]=y;
  }}
}
}
