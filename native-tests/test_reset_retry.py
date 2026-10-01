from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
lib=(root/'app/src/main/cpp/librtlsdr/src/librtlsdr.c').read_text()
reset=lib[lib.index('int rtlsdr_reset_buffer('):lib.index('int rtlsdr_read_sync(')]
code=r"""
#include <assert.h>
#include <stdio.h>
#include "reset_retry.h"
typedef struct {int unused;} rtlsdr_dev_t;
#define USBB 1
#define USB_EPA_CTL 2
static int vals[8],pos,pauses,regs[8];
static int rtlsdr_write_reg(void*d,int b,int reg,int value,int n){regs[pos]=value;return vals[pos++];}
"""+reset+r"""
static int attempt(void*d){return rtlsdr_reset_buffer(d);}
static void pause_once(void){pauses++;}
static void setup(int a,int b,int c,int d){pos=pauses=0;vals[0]=a;vals[1]=b;vals[2]=c;vals[3]=d;}
int main(void){
 rtlsdr_dev_t dev;int n;
 setup(2,2,0,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==0&&n==1&&pos==2&&pauses==0);
 setup(-7,2,2,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==0&&n==2&&pos==3&&pauses==1);
 setup(2,-7,2,2);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==0&&pos==4);assert(regs[2]==0x1002&&regs[3]==0);
 setup(-7,-7,0,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==-7&&n==2&&pos==2);
 setup(-4,0,0,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==-4&&n==1&&pauses==0);
 setup(-9,0,0,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==-9&&n==1);
 setup(1,2,2,0);assert(spectrum_reset_retry(&dev,attempt,pause_once,&n)==0&&n==2);
 puts("PASS: success, transient retry, full reset sequence, bounded failure, disconnect/pipe no retry, short transfer");
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(code)
 subprocess.run(['gcc','-I'+str(root/'app/src/main/cpp'),str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
