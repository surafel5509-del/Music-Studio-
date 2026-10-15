#include "noise_gate.h"
#include <cmath>
#include <algorithm>
namespace wavestudio {
NoiseGate::NoiseGate(){params_={{"thresh","Threshold",-80,-6,-40,-40,"dB"},{"attack","Attack",1,50,5,5,"ms"},{"release","Release",10,500,100,100,"ms"},{"ratio","Reduction",0,100,80,80,"%"}};}
void NoiseGate::set_sample_rate(float sr){Effect::set_sample_rate(sr);}
void NoiseGate::reset(){env_=0;}
void NoiseGate::process(float**b,int nf,int nc){
 float thr=db_to_linear(params_[0].value),atk=powf(0.1f,1.f/(sample_rate_*params_[1].value*0.001f));
 float rel=powf(0.1f,1.f/(sample_rate_*params_[2].value*0.001f)),red=params_[3].value/100.f;
 for(int c=0;c<nc;++c)for(int i=0;i<nf;++i){float x=b[c][i],a=fabsf(x);
  if(a>env_)env_=env_*atk+a*(1-atk);else env_=env_*rel+a*(1-rel);
  b[c][i]=x*(env_>thr?1.f:(1.f-red));
 }
}
}
