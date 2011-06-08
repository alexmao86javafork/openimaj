/**
 * 
 */
package org.openimaj.audio.processor;

import org.openimaj.audio.AudioStream;
import org.openimaj.audio.SampleChunk;

/**
 *	An interface for objects that are able to process audio sample
 *	data. Due to the fact that audio processors provide processed
 *	audio, they are also able to implement the {@link AudioStream} interface
 *	thereby making processors chainable.
 *
 *	@author David Dupplaw <dpd@ecs.soton.ac.uk>
 *  @created 8 Jun 2011
 *	@version $Author$, $Revision$, $Date$
 */
public abstract class AudioProcessor extends AudioStream
{
	/** The audio stream to process in a chain */
	private AudioStream stream = null;
	
	/** The last processed chunk when processing in a chain */
	private SampleChunk currentChunk = null;
	
	/**
	 * 	A default constructor for processing sample chunks
	 * 	in an ad-hoc manner.
	 */
	public AudioProcessor()
	{
	}
	
	/**
	 * 	Construct a new processor based on the given stream. This
	 * 	processor can then be used as a stream itself in a chain.
	 * 
	 *	@param a The audio stream to process.
	 */
	public AudioProcessor( AudioStream a )
	{
		this.stream = a;
		this.format = a.getFormat();
	}
	
	/**
	 * 	Function that takes a sample chunk and processes the chunk.
	 * 	It should also return a sample chunk containing the processed data.
	 * 	If wished, the chunk may be side-affected and the input chunk returned.
	 * 	It should not be assumed that the input chunk will be side-affected,
	 * 	but it must be noted that it is possible that it could be.
	 * 
	 *	@param sample The sample chunk to process.
	 *	@return A sample chunk containing processed data.
	 */
	public abstract SampleChunk process( SampleChunk sample );

	/**
	 *	@inheritDoc
	 * 	@see org.openimaj.audio.AudioStream#nextSampleChunk()
	 */
	@Override
	public SampleChunk nextSampleChunk()
	{
		return currentChunk = process( this.stream.nextSampleChunk() );
	}

	/**
	 *	@inheritDoc
	 * 	@see org.openimaj.audio.Audio#getSampleChunk()
	 */
	@Override
	public SampleChunk getSampleChunk()
	{
		return currentChunk;
	}	
}
