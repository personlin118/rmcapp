#define ENABLED_JNI 1
#if ENABLED_JNI
#include <jni.h>

//#include <utils/Log.h>
#include <android/log.h>
#define INFO_TAG "[INFO]"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "adpcm", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "adpcm", __VA_ARGS__)
#endif
/*---------------------------------------------------------+
|         ADPCM Functions                                  |
+---------------------------------------------------------*/
//#define GET_32(p) (((p)[0]<<24)|((p)[1]<<16)|((p)[2]<<8)|(p)[3])
#define GET_32(p) (((p)[3]<<24)|((p)[2]<<16)|((p)[1]<<8)|(p)[0])

//#define GET_32(p) (((p)[0]<<24)|((p)[1]<<16)|((p)[2]<<8)|(p)[3])
#define BOUND(x,low,high) {if(x<low) x=low; else if(x>high) x=high;}

/*
** Intel/DVI ADPCM coder/decoder.
**
** The algorithm for this coder was taken from the IMA Compatability Project
** proceedings, Vol 2, Number 2; May 1992.
**
** - The NODIVMUL define has been removed. Computations are now always done
**   using shifts, adds and subtracts. It turned out that, because the standard
**   is defined using shift/add/subtract, you needed bits of fixup code
**   (because the div/mul simulation using shift/add/sub made some rounding
**   errors that real div/mul don't make) and all together the resultant code
**   ran slower than just using the shifts all the time.
*/
typedef struct tagADPCMState
{
	int ValPrev;
	int Index;
}ADPCMState;

/* Intel ADPCM step variation table */
static int IndexTable[16] =
{
	-1, -1, -1, -1, 2, 4, 6, 8,
	-1, -1, -1, -1, 2, 4, 6, 8,
};

static int StepSizeTable[89] =
{
	7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
	19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
	50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
	130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
	337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
	876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
	2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
	5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
	15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
};


static void adpcm_coder( const short *in_data_ptr, char *out_data_ptr, int len, ADPCMState *state )
{
	int val, val_pred;    // Current input sample value, predicted output value
	int diff;    // Difference between val and val_prev
	int step;    // Stepsize
	int index;    // Current step change index
	int code;    // 包含sign與delta...
	int vp_diff;    // Current change to val_pred
	int output_buffer=0;
	// place to keep previous 4-bit value
	// 為了抑制最佳化警告...
	int buffer_step;    // toggle between output_buffer/output

//  val_pred=state->ValPrev;
	val_pred=state->ValPrev<<3;
	index=state->Index;
//  step=StepSizeTable[index];
	step=StepSizeTable[index]<<3;
	for( buffer_step=1; len>0; len-- )
	{
//    val=*in_data_ptr++;
		val=(*in_data_ptr++)<<3;
// Step 1 - compute difference with previous value
		diff=val-val_pred;
		if( diff>=0 )
			code=0;
		else
		{
			code=8;
			diff=-diff;
		}
// Step 2 - Divide and clamp
// Note:
// This code approximately computes:
//   delta=diff*4/step;
//   vp_diff=(delta+0.5)*step/4;
// but in shift step bits are dropped. The net result of this is
// that even if you have fast mul/div hardware you cannot put it to
// good use since the fixup would be too expensive.
		vp_diff=step>>3;
		if( diff>=step )
		{
			code|=4;
			diff-=step;
			vp_diff+=step;
		}
		step>>=1;
		if( diff>=step )
		{
			code|=2;
			diff-=step;
			vp_diff+=step;
		}
		step>>=1;
		if( diff>=step )
		{
			code|=1;
			vp_diff+=step;
		}
// Step 3 - Update previous value
		if( (code&0x08)!=0 )    val_pred-=vp_diff;
		else    val_pred+=vp_diff;
// Step 4 - Clamp previous value to 16 bits
//    BOUND( val_pred, -32768, 32767 );
		BOUND( val_pred, -32768*8, 32767*8 );
// Step 5 - Assemble value, update index and step values
		index+=IndexTable[code];
		BOUND( index, 0, 88 );
//    step=StepSizeTable[index];
		step=StepSizeTable[index]<<3;
// Step 6 - Output value
		if( buffer_step )
			output_buffer=(code<<4)&0xF0;
		else
			*out_data_ptr++=(code&0x0F)|output_buffer;
		buffer_step=!buffer_step;
	}
// Output last step, if needed
	if( !buffer_step )
		*out_data_ptr++=output_buffer;
//  state->ValPrev=val_pred;
	state->ValPrev=val_pred>>3;
	state->Index=index;
}


static void adpcm_decoder( const char *in_data_ptr, short *out_data_ptr, int len, const ADPCMState *state )
{
	int val_pred;    // Predicted value
	int vp_diff;    // Current change to val_pred
	int code;    // 包含sign與delta...
	int step;    // Stepsize
	int index;    // Current step change index
	int input_buffer=0;
	// place to keep next 4-bit value
	// 為了抑制最佳化警告...
	int buffer_step;    // toggle between input_buffer/input

//  val_pred=state->ValPrev;
	val_pred=state->ValPrev<<3;
	index=state->Index;
//  step=StepSizeTable[index];
	step=StepSizeTable[index]<<3;
	for ( buffer_step=0; len>0 ; len-- )
	{
// Step 1 - get the delta value
		if( buffer_step )
			code=input_buffer&0xF;
		else
		{
			input_buffer=*in_data_ptr++;
			code=(input_buffer>>4)&0xF;
		}
		buffer_step=!buffer_step;
// Step 2 - Find new index value (for later)
		index+=IndexTable[code];
		BOUND( index, 0, 88 );
// Step 3 - Compute difference and new predicted value
// Computes vp_diff=(delta+0.5)*step/4, but see comment
// in adpcm_coder.
		vp_diff=step>>3;
		if( (code&0x04)!=0 )    vp_diff+=step;
		if( (code&0x02)!=0 )    vp_diff+=step>>1;
		if( (code&0x01)!=0 )    vp_diff+=step>>2;
		if( (code&0x08)!=0 )
			val_pred-=vp_diff;
		else
			val_pred+=vp_diff;
// Step 4 - clamp output value
//    BOUND( val_pred, -32768, 32767 );
		BOUND( val_pred, -32768*8, 32767*8 );
// Step 5 - Update step value
//    step=StepSizeTable[index];
		step=StepSizeTable[index]<<3;
// Step 6 - Output value
//    *out_data_ptr++=val_pred;
		*out_data_ptr++=val_pred>>3;
	}
}


// 固定壓成1/4左右的大小
int EncodeADPCM( const short *src_data_ptr, int src_data_size,
				  void *dest_buf_ptr, int dest_buf_size, ADPCMState *state_ptr )
{
	int data_count, adpcm_size;

	data_count=src_data_size/2;
	adpcm_size=4*3+data_count/2+data_count%2;    // 壓縮出來的大小！！！

	if( dest_buf_size<adpcm_size )
		return( -1 );

	*(unsigned int *) dest_buf_ptr=data_count;
	*(((unsigned int *) dest_buf_ptr)+1)=state_ptr->ValPrev;
	*(((unsigned int *) dest_buf_ptr)+2)=state_ptr->Index;

	adpcm_coder( src_data_ptr, ((char *) dest_buf_ptr)+4*3, data_count, state_ptr );
	return adpcm_size;
}

inline unsigned int PACK(unsigned char c0, unsigned char c1, unsigned char c2, unsigned char c3) {
    return (c0 << 24) | (c1 << 16) | (c2 << 8) | c3;
}
#define GET32(c) PACK((c)[0], (c)[1], (c)[2], (c)[3])

int DecodeADPCM( const unsigned char *src_data_ptr, short *dest_buf_ptr, int dest_buf_size)
{
	int data_count;
	ADPCMState state;

	//data_count=*(unsigned int *) src_data_ptr;
	//state.ValPrev=*(((unsigned int *) src_data_ptr)+1);
	//state.Index=*(((unsigned int *) src_data_ptr)+2);
	data_count = GET_32(src_data_ptr);
	state.ValPrev = GET_32(src_data_ptr+4);
	state.Index = GET_32(src_data_ptr+8);
	LOGD("count=%d, ValPrev=%d, Index=%d, char size=%d\n", data_count, state.ValPrev, state.Index, sizeof(char));
	if( dest_buf_size<data_count*2 )
		return( -1 );

	adpcm_decoder( ((const char *) src_data_ptr)+4*3, dest_buf_ptr, data_count, &state );
	return data_count*2;
}

#if ENABLED_JNI
JNIEXPORT void JNICALL Java_com_dynamas_MobileViewer_LiveAudioPlayer_testAdpcm(JNIEnv * env, jobject thiz)
{
	//LOGI("Hello LIB!\n");
}

JNIEXPORT jint JNICALL Java_com_dynamas_MobileViewer_LiveAudioPlayer_decodeAdpcm(JNIEnv * env, jobject thiz, jbyteArray src, jint idx, jshortArray dest)
{
	static jshort tmp[8000];
	jint res = 0;
	jint str_out_len = (*env)->GetArrayLength(env, dest);
#if 0
	static jbyte src_ptr[1000];
	jint str_len = (*env)->GetArrayLength(env, src);
	(*env)->GetByteArrayRegion( env, src, 0 , str_len, src_ptr) ;
#else
	jboolean isCopy = JNI_FALSE;
	//jbyte *src_ptr = (*env)->GetByteArrayElements(env, src, &isCopy);
	jint str_len = (*env)->GetArrayLength(env, src);
	jbyte *src_ptr = (*env)->GetByteArrayElements(env, src, 0);
#endif
	LOGD("recv length = %d, idx=%d\n", str_len, idx);
	res = DecodeADPCM((const unsigned char*)src_ptr+idx, (short *)tmp, 8000);
	LOGD("recv length = %d, out_buffer_len=%d, result=%d\n", str_len, str_out_len, res);

	if(res > 0)
	{
		(*env)->SetShortArrayRegion( env, dest, 0, res, tmp);
	}
	(*env)->ReleaseByteArrayElements(env, src, src_ptr, JNI_ABORT);
	return res;
}
#endif
