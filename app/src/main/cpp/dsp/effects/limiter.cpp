#include "limiter.h"
#include <cmath>
#include <algorithm>
namespace wavestudio {
Limiter::Limiter(){params_={{"ceiling","Ceiling",-6,0,-0.5f,-0.5f,"dB"},{"release","Release",10,500,100,100,"ms"},{"gain","Gain",-12,12,0,0,"dB"}};}
void Limiter::set_sample_rate(float sr){Effect::set_sample_rate(sr);}
void Limiter::reset(){peak_=0;}
void Limiter::process(float**b,int nf,int nc){
 float thr=db_to_linear(params_[0].value),inG=db_to_linear(params_[2].value);
 float relCoef=powf(0.1f,1.f/(sample_rate_*params_[1].value*0.001f));
 for(int c=0;c<nc;++c)for(int i=0;i<nf;++i){
  float x=b[c][i]*inG,a=fabsf(x);
  if(a>peak_)peak_=peak_*0.9995f+a*(1-0.9995f);else peak_=peak_*relCoef+a*(1-relCoef);
  float g=peak_>thr?thr/peak_:1.f; b[c][i]=x*g;
 }
}
}
