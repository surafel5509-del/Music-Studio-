#include "deesser.h"
#include <cmath>
#include <algorithm>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
namespace wavestudio {
DeEsser::DeEsser(){params_={{"freq","S-Freq",2000,12000,6000,6000,"Hz"},{"amount","Amount",0,100,30,30,"%"}};}
void DeEsser::set_sample_rate(float sr){Effect::set_sample_rate(sr);}
void DeEsser::reset(){bq_=BQ();}
void DeEsser::process(float**b,int nf,int nc){
 float fc=params_[0].value,amt=params_[1].value/100.f;
 float w0=2*(float)M_PI*fc/sample_rate_,c=cosf(w0),s=sinf(w0),a=s/(2*0.7f);
 float a0=1+a,b0=(1-c)/2/a0,b1=(1-c)/a0,b2=(1-c)/2/a0,a1=-2*c/a0,a2=(1-a)/a0;
 for(int ch=0;ch<nc;++ch){float senv=0;
  for(int i=0;i<nf;++i){float x=b[ch][i];
   float y=b0*x+b1*bq_.x1+b2*bq_.x2-a1*bq_.y1-a2*bq_.y2;
   bq_.x2=bq_.x1;bq_.x1=x;bq_.y2=bq_.y1;bq_.y1=y;
   float e=fabsf(y); senv=senv*0.99f+e*0.01f;
   b[ch][i]=x*(1.f-amt*std::min(1.f,senv*4.f));
  }}
}
}
