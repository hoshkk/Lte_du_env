from pathlib import Path
import tempfile,subprocess
r=Path(__file__).resolve().parents[1]
s=(r/'app/src/main/cpp/librtlsdr/libusb/libusb/os/linux_usbfs.c').read_text()
code=s[s.index('int spectrum_usb_control('):s.index('static void op_close(')]
prefix=r"""
#include <stdint.h>
#include <errno.h>
#include <assert.h>
#include <stdio.h>
#define LIBUSB_ERROR_NO_DEVICE -4
#define LIBUSB_ERROR_TIMEOUT -7
#define LIBUSB_ERROR_PIPE -9
#define LIBUSB_ERROR_INTERRUPTED -10
#define LIBUSB_ERROR_ACCESS -3
#define LIBUSB_ERROR_BUSY -6
#define LIBUSB_ERROR_INVALID_PARAM -2
#define LIBUSB_ERROR_IO -1
#define IOCTL_USBFS_CONTROL 123
typedef struct {int x;} libusb_device_handle;
struct linux_device_handle_priv {int fd;};
struct usbfs_ctrltransfer {uint8_t bmRequestType,bRequest;uint16_t wValue,wIndex,wLength;unsigned int timeout;void*data;};
static struct linux_device_handle_priv hp={7};
static void *_device_handle_priv(void*h){return &hp;}
static int result=2,error_code=0,calls=0,fallback=0;
static int ioctl(int fd,int cmd,struct usbfs_ctrltransfer*c){
 calls++;assert(fd==7&&cmd==123);assert(c->bmRequestType==0x40&&c->bRequest==0);
 assert(c->wValue==0x34&&c->wIndex==0x610&&c->wLength==2&&c->timeout==300);
 errno=error_code;return result;
}
static int libusb_control_transfer(void*h,uint8_t t,uint8_t r,uint16_t v,uint16_t i,unsigned char*d,uint16_t n,unsigned int timeout){fallback++;return 2;}
"""
main=r"""
int main(void){
 libusb_device_handle h;
 assert(spectrum_usb_control(&h,0x40,0,0x34,0x610,0,2,300)==2);
 result=-1;int es[]={ENODEV,ETIMEDOUT,EPIPE,EINTR,EACCES,EBUSY,EINVAL,EIO};
 int want[]={-4,-7,-9,-10,-3,-6,-2,-1};
 for(int i=0;i<8;i++){error_code=es[i];assert(spectrum_usb_control(&h,0x40,0,0x34,0x610,0,2,300)==want[i]);}
 assert(fallback==0);
 error_code=ENOTTY;assert(spectrum_usb_control(&h,0x40,0,0x34,0x610,0,2,300)==2&&fallback==1);
 puts("PASS: unchanged request fields, return bytes, error mapping, unsupported fallback only");
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'test.c').write_text(prefix+code+main)
 subprocess.run(['gcc',str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
