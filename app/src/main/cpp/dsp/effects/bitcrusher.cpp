#include "bitcrusher.h"
#include <cmath>
namespace wavestudio {
BitCrusher::BitCrusher(){params_={{"bits","Bits",1,16,8,8,""},{"downsample","Downsample",1,16,1,1,"x"},{"mix","Mix",0,100,100,100,"%"}};}
void BitCrusher::process(float**b,int nf,int nc){
 float bits=params_[0].value,ds=params_[1].value,wet=params_[2].value/100.f;
 float steps=powf(2,bits-1); float hold[2]={0,0}; int cnt[2]={0,0};
 for(int c=0;c<nc;++c)for(int i=0;i<nf;++i){
  if(cnt[c]++>=(int)ds){cnt[c]=0;hold[c]=roundf(b[c][i]*steps)/steps;}
  b[c][i]=b[c][i]*(1-wet)+hold[c]*wet;
 }
}
}
