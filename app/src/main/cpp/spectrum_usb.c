#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <stdio.h>
#include <android/log.h>
#include "reset_retry.h"
#include "rtl-sdr.h"
#include "rtl-sdr-android.h"

static void fail(JNIEnv *e,const char *s){(*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),s);}
static int reset_once(void *d){return rtlsdr_reset_buffer((rtlsdr_dev_t*)d);}
static void retry_pause(void){struct timespec ts={0,5000000L};nanosleep(&ts,0);}
static int reset_checked(JNIEnv *e,rtlsdr_dev_t *d){
 int attempts=0,rc=spectrum_reset_retry(d,reset_once,retry_pause,&attempts);
 if(attempts>1)__android_log_print(ANDROID_LOG_WARN,"SpectrumUSB","FIFO retry: rc=%d",rc);
 if(rc<0){
   char msg[192];
   snprintf(msg,sizeof(msg),"USB FIFO 초기화 실패 (USB 코드 %d, 시도 %d회). 실측 시작으로 다시 연결하세요",rc,attempts);
   fail(e,msg);return 0;
 }
 return 1;
}
JNIEXPORT jlong JNICALL Java_com_lteduenv_spectrum_data_sdr_NativeRtl_open(JNIEnv *e,jobject o,jint fd,jstring path,jboolean agc,jint gain){
 const char *p=(*e)->GetStringUTFChars(e,path,0);rtlsdr_dev_t *d=0;
 int rc=rtlsdr_open2(&d,fd,p);(*e)->ReleaseStringUTFChars(e,path,p);
 if(rc<0 || !d){char msg[192];snprintf(msg,sizeof(msg),"USB 열기 실패 (코드 %d). SDR Driver를 종료하고 OTG를 다시 연결하세요",rc);fail(e,msg);return 0;}
 enum rtlsdr_tuner t=rtlsdr_get_tuner_type(d);
 if(t!=RTLSDR_TUNER_R820T && t!=RTLSDR_TUNER_R828D){rtlsdr_close(d);fail(e,"R820T/R828D 튜너만 지원합니다");return 0;}
 if(rtlsdr_set_sample_rate(d,2400000)<0 || rtlsdr_set_agc_mode(d,0)<0 ||
    rtlsdr_set_tuner_gain_mode(d,agc?0:1)<0 || (!agc && rtlsdr_set_tuner_gain(d,gain)<0)){
   rtlsdr_close(d);fail(e,"USB 수신 설정 실패");return 0;
 }
 return (jlong)(intptr_t)d;
}
static jlong clock_ns(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (jlong)t.tv_sec*1000000000LL+t.tv_nsec;}
JNIEXPORT jlongArray JNICALL Java_com_lteduenv_spectrum_data_sdr_NativeRtl_tune(JNIEnv *e,jobject o,jlong h,jint hz,jint settle){
 rtlsdr_dev_t *d=(rtlsdr_dev_t*)(intptr_t)h;
 if(!d || settle<0 || settle>1000){fail(e,"잘못된 USB 주파수 설정");return NULL;}
 jlong t0=clock_ns();
 if(rtlsdr_set_center_freq(d,(uint32_t)hz)<0){fail(e,"주파수 동조 실패 / PLL 잠금 확인 실패");return NULL;}
 jlong t1=clock_ns();
 struct timespec ts={settle/1000,(settle%1000)*1000000L};nanosleep(&ts,0);
 jlong t2=clock_ns();
 /* No async transfers or host sample queues exist in this backend. Flush after settling. */
 if(!reset_checked(e,d))return NULL;
 jlong v[3]={t1-t0,t2-t1,clock_ns()-t2};
 jlongArray result=(*e)->NewLongArray(e,3);
 if(result)(*e)->SetLongArrayRegion(e,result,0,3,v);
 return result;
}
JNIEXPORT jint JNICALL Java_com_lteduenv_spectrum_data_sdr_NativeRtl_read(JNIEnv *e,jobject o,jlong h,jbyteArray a,jint n){
 if(!h || n<=0 || n>16384 || n%512 || n>(*e)->GetArrayLength(e,a)){fail(e,"잘못된 USB 읽기 크기");return 0;}
 unsigned char b[16384];int got=0;
 int rc=rtlsdr_read_sync((rtlsdr_dev_t*)(intptr_t)h,b,n,&got);
 if(rc<0 || got<=0 || got%2){fail(e,"USB 수신 실패 또는 시간 초과. 연결을 확인하세요");return 0;}
 (*e)->SetByteArrayRegion(e,a,0,got,(jbyte*)b);return got;
}
JNIEXPORT void JNICALL Java_com_lteduenv_spectrum_data_sdr_NativeRtl_close(JNIEnv *e,jobject o,jlong h){if(h)rtlsdr_close((rtlsdr_dev_t*)(intptr_t)h);}

JNIEXPORT void JNICALL Java_com_lteduenv_spectrum_data_sdr_NativeRtl_reset(JNIEnv *e,jobject o,jlong h){
 if(!h){fail(e,"USB 연결 없음");return;}
 reset_checked(e,(rtlsdr_dev_t*)(intptr_t)h);
}
