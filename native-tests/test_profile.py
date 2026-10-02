from pathlib import Path
import tempfile,subprocess
r=Path(__file__).resolve().parents[1]
s=(r/'app/src/main/cpp/librtlsdr/src/librtlsdr.c').read_text()
code=s[s.index('/* Thread-local: profiling'):s.index('int rtlsdr_read_array(')]
prefix="""
#include <stdint.h>
#include <string.h>
#include <assert.h>
#include <stdio.h>
typedef void libusb_device_handle;
#define IICB 6
static int calls, answer=2;
static int libusb_control_transfer(libusb_device_handle*h,uint8_t t,uint8_t r,uint16_t v,uint16_t i,unsigned char*d,uint16_t l,unsigned int timeout){calls++;return answer;}
"""
prefix += "\n#define spectrum_usb_control libusb_control_transfer\n"
main="""
int main(void){
 int64_t stats[8];
 spectrum_profile_begin();
 assert(spectrum_profile_last_write()==0);
 assert(spectrum_control_transfer(0,0,0,0,0x610,0,2,1)==2);
 int64_t last=spectrum_profile_last_write(); assert(last>0);
 spectrum_control_transfer(0,0x80,0,0,0x600,0,2,1);
 assert(spectrum_profile_last_write()==last);
 answer=-7;assert(spectrum_control_transfer(0,0,0,0,0x110,0,2,1)==-7);
 assert(spectrum_profile_last_write()==0);
 spectrum_profile_stage(0,spectrum_clock_ns()-100);
 spectrum_profile_stage(1,spectrum_clock_ns()-100);
 spectrum_profile_end(stats);
 assert(stats[5]==1&&stats[6]==1&&stats[7]==1);
 assert(stats[0]>=100&&stats[1]>=100);
 for(int i=2;i<5;i++)assert(stats[i]>=0);
 spectrum_control_transfer(0,0,0,0,0x610,0,2,1);
 spectrum_profile_end(stats);assert(stats[5]==1);
 spectrum_profile_begin();spectrum_profile_end(stats);
 for(int i=0;i<8;i++)assert(stats[i]==0);
 assert(calls==4);
 puts("PASS: USB classification, counts, error propagation, disabled bypass, stage timing, per-tune reset");
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(prefix+code+main)
 subprocess.run(['gcc',str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
