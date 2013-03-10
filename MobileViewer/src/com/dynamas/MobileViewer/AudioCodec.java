/*
Filename: AudioCodec.java
Purpose:
Description:
*/

package com.dynamas.MobileViewer;
import android.util.Log;


// import com.dynamas.MobileViewer.ADPCMState;
class ADPCMState {
	int ValPrev;
	int Index;
}


/*---------------------------------------------------------+
|         ADPCM Functions                                  |
+---------------------------------------------------------*/
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

public class AudioCodec {
	private static final String DTag = "APCM";
	/* Intel ADPCM step variation table */
	static final int[] IndexTable = new int[]{
		-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8,
	};

	static final int[] StepSizeTable = new int[]{
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

	static int BOUND(int x, int low, int high) {
		if (x < low) return low;
		else if (x > high) return high;
		return x;
	}

	static void adpcm_coder(short[] in_data_ptr, byte[] out_data_ptr, int idx, int len, ADPCMState state) {
		int val, val_pred; // Current input sample value, predicted output value
		int diff; // Difference between val and val_prev
		int step; // Stepsize
		int index; // Current step change index
		byte code; // 包含sign與delta...
		int vp_diff; // Current change to val_pred
		byte output_buffer = 0;
		int s = 0, d = idx;
		// place to keep previous 4-bit value
		// 為了抑制最佳化警告...
		boolean buffer_step; // toggle between output_buffer/output

		//  val_pred=state->ValPrev;
		val_pred = state.ValPrev << 3;
		index = state.Index;
		//  step=StepSizeTable[index];
		step = StepSizeTable[index] << 3;
		for (buffer_step = true; len > 0; len--) {
			//    val=*in_data_ptr++;
			val = (in_data_ptr[s++]) << 3;
			// Step 1 - compute difference with previous value
			diff = val - val_pred;
			if (diff >= 0) code = 0;
			else {
				code = 8;
				diff = -diff;
			}
			// Step 2 - Divide and clamp
			// Note:
			// This code approximately computes:
			//   delta=diff*4/step;
			//   vp_diff=(delta+0.5)*step/4;
			// but in shift step bits are dropped. The net result of this is
			// that even if you have fast mul/div hardware you cannot put it to
			// good use since the fixup would be too expensive.
			vp_diff = step >> 3;
			if (diff >= step) {
				code |= 4;
				diff -= step;
				vp_diff += step;
			}
			step >>= 1;
			if (diff >= step) {
				code |= 2;
				diff -= step;
				vp_diff += step;
			}
			step >>= 1;
			if (diff >= step) {
				code |= 1;
				vp_diff += step;
			}
			// Step 3 - Update previous value
			if ((code & 0x08) != 0) val_pred -= vp_diff;
			else val_pred += vp_diff;
			// Step 4 - Clamp previous value to 16 bits
			//    BOUND( val_pred, -32768, 32767 );
			val_pred = BOUND(val_pred, -32768 * 8, 32767 * 8);
			// Step 5 - Assemble value, update index and step values
			index += IndexTable[code];
			index = BOUND(index, 0, 88);
			//    step=StepSizeTable[index];
			step = StepSizeTable[index] << 3;
			// Step 6 - Output value
			if (buffer_step) output_buffer = (byte)(((int)code << 4) & 0xF0);
			else out_data_ptr[d++] = (byte)(((int)code & 0x0F) | output_buffer);
			buffer_step = !buffer_step;
		}
		// Output last step, if needed
		if (!buffer_step) out_data_ptr[d++] = output_buffer;
		//  state->ValPrev=val_pred;
		state.ValPrev = val_pred >> 3;
		state.Index = index;
	}


	static void adpcm_decoder(byte[] in_data_ptr, int sidx, short[] out_data_ptr, int len, ADPCMState state) {
		int val_pred; // Predicted value
		int vp_diff; // Current change to val_pred
		int code; // 包含sign與delta...
		int step; // Stepsize
		int index; // Current step change index
		int input_buffer = 0;
		// place to keep next 4-bit value
		// 為了抑制最佳化警告...
		boolean buffer_step; // toggle between output_buffer/output
		int s = sidx, d = 0;

		//  val_pred=state->ValPrev;
		val_pred = state.ValPrev << 3;
		index = state.Index;
		//  step=StepSizeTable[index];
		step = StepSizeTable[index] << 3;
		for (buffer_step = false; len > 0; len--) {
			// Step 1 - get the delta value
			if (buffer_step) code = input_buffer & 0xF;
			else {
				input_buffer = in_data_ptr[s++];
				code = (input_buffer >> 4) & 0xF;
			}
			buffer_step = !buffer_step;
			// Step 2 - Find new index value (for later)
			index += IndexTable[code];
			index = BOUND(index, 0, 88);
			// Step 3 - Compute difference and new predicted value
			// Computes vp_diff=(delta+0.5)*step/4, but see comment
			// in adpcm_coder.
			vp_diff = step >> 3;
			if ((code & 0x04) != 0) vp_diff += step;
			if ((code & 0x02) != 0) vp_diff += step >> 1;
			if ((code & 0x01) != 0) vp_diff += step >> 2;
			if ((code & 0x08) != 0) val_pred -= vp_diff;
			else val_pred += vp_diff;
			// Step 4 - clamp output value
			//    BOUND( val_pred, -32768, 32767 );
			val_pred = BOUND(val_pred, -32768 * 8, 32767 * 8);
			// Step 5 - Update step value
			//    step=StepSizeTable[index];
			step = StepSizeTable[index] << 3;
			// Step 6 - Output value
			//    *out_data_ptr++=val_pred;
			out_data_ptr[d++] = (short)((int)val_pred >> 3);
		}
	}
	// 固定壓成1/4左右的大小
	private ADPCMState m_state;
	int EncodeADPCM(short[] src_data_ptr, int idx, int src_data_size,
		byte[] dest_buf_ptr, int dest_buf_size) {
		int data_count, adpcm_size;

		//Assert(src_data_size >= 0 && src_data_size % 2 == 0);

		data_count = src_data_size / 2;
		adpcm_size = 4 * 3 + data_count / 2 + data_count % 2; // 壓縮出來的大小！！！

		if (dest_buf_size < adpcm_size) return -1;

        Tools.ToArray(dest_buf_ptr, idx, data_count);
        Tools.ToArray(dest_buf_ptr, idx + 4, m_state.ValPrev);
        Tools.ToArray(dest_buf_ptr, idx + 8, m_state.Index);
		// * (DWord * ) dest_buf_ptr = data_count; 
		// * (((DWord * ) dest_buf_ptr) + 1) = m_state->ValPrev; 
		// * (((DWord * ) dest_buf_ptr) + 2) = m_state->Index;
		adpcm_coder(src_data_ptr, dest_buf_ptr, idx + 4 * 3, data_count, m_state);
		// if (adpcm_size_ptr != NULL) * adpcm_size_ptr = adpcm_size;
		return adpcm_size;
	}
	
	public static int DecodeADPCM(byte[] src_data_ptr, int idx, short[] dest_buf_ptr, int dest_buf_size) {
		int data_count;
		ADPCMState state = new ADPCMState();
		data_count = Tools.ToInt(src_data_ptr, idx + 0);
		state.ValPrev = Tools.ToInt(src_data_ptr, idx + 4);
		state.Index = Tools.ToInt(src_data_ptr, idx + 8);
		Log.d( DTag, "count="+data_count+", ValPrev="+state.ValPrev+", Index="+state.Index+"\n");
		if (dest_buf_size < data_count * 2) return -1;

		adpcm_decoder(src_data_ptr, idx + 4 * 3, dest_buf_ptr, data_count, state);
		data_count *= 2;
		return data_count;
	}
	
}