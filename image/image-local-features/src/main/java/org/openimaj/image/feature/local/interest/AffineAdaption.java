package org.openimaj.image.feature.local.interest;

import java.io.IOException;
import java.util.List;

import javax.swing.JFrame;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.openimaj.image.DisplayUtilities;
import org.openimaj.image.FImage;
import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.image.colour.RGBColour;
import org.openimaj.image.feature.local.interest.AbstractStructureTensorIPD.InterestPointData;
import org.openimaj.image.pixel.Pixel;
import org.openimaj.image.processing.convolution.FConvolution;
import org.openimaj.image.processing.convolution.FGaussianConvolve;
import org.openimaj.image.processing.resize.ResizeProcessor;
import org.openimaj.image.processing.transform.ProjectionProcessor;
import org.openimaj.math.geometry.point.Point2dImpl;
import org.openimaj.math.geometry.shape.Ellipse;
import org.openimaj.math.geometry.shape.EllipseUtilities;
import org.openimaj.math.geometry.shape.Rectangle;
import org.openimaj.math.matrix.EigenValueVectorPair;
import org.openimaj.math.matrix.MatrixUtils;

import Jama.EigenvalueDecomposition;
import Jama.Matrix;

public class AffineAdaption {
	private static final FImage LAPLACIAN_KERNEL = new FImage(new float[][] {{2, 0, 2}, {0, -8, 0}, {2, 0, 2}});
	private static final FImage DX_KERNEL = new FImage(new float[][] {{-1, 0, 1}});
	private static final FImage DY_KERNEL = new FImage(new float[][] {{-1}, {0}, {1}});
	
	static Logger logger = Logger.getLogger(AffineAdaption.class);
	static{
		BasicConfigurator.configure();
		logger.setLevel(Level.OFF);
	}
	/*
	 * Calculates second moments matrix in point p
	 */
	Matrix calcSecondMomentMatrix(final FImage dx2, final FImage dxy, final FImage dy2, Pixel p) {
		int x = p.x;
		int y = p.y;

		Matrix M = new Matrix(2, 2);
		M.set(0, 0, dx2.pixels[y][x]);
		M.set(0, 1, dxy.pixels[y][x]);
		M.set(1, 0, dxy.pixels[y][x]);
		M.set(1, 1, dy2.pixels[y][x]);
		
		return M;
	}

	/*
	 * Performs affine adaptation
	 */
	boolean calcAffineAdaptation(final FImage fimage, EllipticKeyPoint keypoint) {
		DisplayUtilities.createNamedWindow("warp", "Warped Image ROI",true);
		Matrix transf = new Matrix(2, 3); 	// Transformation matrix
		Point2dImpl c = new Point2dImpl(); 	// Transformed point
		Point2dImpl p = new Point2dImpl(); 	// Image point

		Matrix U = Matrix.identity(2, 2); 	// Normalization matrix

		Matrix Mk = U.copy(); 
		FImage Lxm2smooth = new FImage(1,1), Lym2smooth = new FImage(1,1), Lxmysmooth = new FImage(1,1), img_roi, warpedImg = new FImage(1,1);
		float Qinv = 1, q, si = keypoint.si, sd = 0.75f * si;
		boolean divergence = false, convergence = false;
		int i = 0;

		//Coordinates in image
		int py = keypoint.centre.y;
		int px = keypoint.centre.x;

		//Roi coordinates
		int roix, roiy;

		//Coordinates in U-trasformation
		int cx = px;
		int cy = py;
		int cxPr = cx;
		int cyPr = cy;

		float radius = keypoint.size / 2 * 1.4f;
		float half_width, half_height;

		Rectangle roi;
		float ax1, ax2;
		double phi = 0;
		ax1 = ax2 = keypoint.size / 2;

		//Affine adaptation
		while (i <= 10 && !divergence && !convergence)
		{
			//Transformation matrix 
			MatrixUtils.zero(transf);
			transf.setMatrix(0, 1, 0, 1, U);
			
			keypoint.transf = transf.copy();

			Rectangle boundingBox = new Rectangle();

			double ac_b2 = U.det();
			boundingBox.width = (float) Math.ceil(U.get(1, 1)/ac_b2  * 3 * si*1.4 );
			boundingBox.height = (float) Math.ceil(U.get(0, 0)/ac_b2 * 3 * si*1.4 );

			//Create window around interest point
			half_width = Math.min((float) Math.min(fimage.width - px-1, px), boundingBox.width);
			half_height = Math.min((float) Math.min(fimage.height - py-1, py), boundingBox.height);
			
			if (half_width <= 0 || half_height <= 0) return divergence;
			
			roix = Math.max(px - (int) boundingBox.width, 0);
			roiy = Math.max(py - (int) boundingBox.height, 0);
			roi = new Rectangle(roix, roiy, px - roix + half_width+1, py - roiy + half_height+1);

			//create ROI
			img_roi = fimage.extractROI(roi);

			//Point within the ROI
			p.x = px - roix;
			p.y = py - roiy;

			//Find coordinates of square's angles to find size of warped ellipse's bounding box
			float u00 = (float) U.get(0, 0);
			float u01 = (float) U.get(0, 1);
			float u10 = (float) U.get(1, 0);
			float u11 = (float) U.get(1, 1);

			float minx = u01 * img_roi.height < 0 ? u01 * img_roi.height : 0;
			float miny = u10 * img_roi.width < 0 ? u10 * img_roi.width : 0;
			float maxx = (u00 * img_roi.width > u00 * img_roi.width + u01 * img_roi.height ? u00
					* img_roi.width : u00 * img_roi.width + u01 * img_roi.height) - minx;
			float maxy = (u11 * img_roi.width > u10 * img_roi.width + u11 * img_roi.height ? u11
					* img_roi.height : u10 * img_roi.width + u11 * img_roi.height) - miny;

			//Shift
			transf.set(0, 2, -minx);
			transf.set(1, 2, -miny);

			if (maxx >=  2*radius+1 && maxy >=  2*radius+1)
			{
				//Size of normalized window must be 2*radius
				//Transformation
				FImage warpedImgRoi;
				ProjectionProcessor<Float, FImage> proc = new ProjectionProcessor<Float, FImage>();
				proc.setMatrix(transf);
				img_roi.process(proc);
				warpedImgRoi = proc.performProjection(0, (int)maxx, 0, (int)maxy, null);

//				DisplayUtilities.displayName(warpedImgRoi.clone().normalise(), "warp");
				
				//Point in U-Normalized coordinates
				c = p.transform(U);
				cx = (int) (c.x - minx);
				cy = (int) (c.y - miny);
				
				


				if (warpedImgRoi.height > 2 * radius+1 && warpedImgRoi.width > 2 * radius+1)
				{
					//Cut around normalized patch
					roix = (int) Math.max(cx - Math.ceil(radius), 0.0);
					roiy = (int) Math.max(cy - Math.ceil(radius), 0.0);
					roi = new Rectangle(roix, roiy,
							cx - roix + (float)Math.min(Math.ceil(radius), warpedImgRoi.width - cx-1)+1,
							cy - roiy + (float)Math.min(Math.ceil(radius), warpedImgRoi.height - cy-1)+1);
					warpedImg = warpedImgRoi.extractROI(roi);

					//Coordinates in cutted ROI
					cx = cx - roix;
					cy = cy - roiy;
				} else {
					warpedImg.internalAssign(warpedImgRoi);
				}
				
				if(logger.getLevel() == Level.DEBUG){
					displayCurrentPatch(img_roi.clone().normalise(),p.x,p.y,warpedImg.clone().normalise(),cx,cy,U,sd);
				}
				
				//Integration Scale selection
				si = selIntegrationScale(warpedImg, si, new Pixel(cx, cy));

				//Differentation scale selection
				sd = selDifferentiationScale(warpedImg, Lxm2smooth, Lxmysmooth, Lym2smooth, si, new Pixel(cx, cy));

				//Spatial Localization
				cxPr = cx; //Previous iteration point in normalized window
				cyPr = cy;

				float cornMax = 0;
				for (int j = 0; j < 3; j++)
				{
					for (int t = 0; t < 3; t++)
					{
						float dx2 = Lxm2smooth.pixels[cyPr - 1 + j][cxPr - 1 + t];
						float dy2 = Lym2smooth.pixels[cyPr - 1 + j][cxPr - 1 + t];
						float dxy = Lxmysmooth.pixels[cyPr - 1 + j][cxPr - 1 + t];
						float det = dx2 * dy2 - dxy * dxy;
						float tr = dx2 + dy2;
						float cornerness = (float) (det - (0.04 * Math.pow(tr, 2)));
						
						if (cornerness > cornMax) {
							cornMax = cornerness;
							cx = cxPr - 1 + t;
							cy = cyPr - 1 + j;
						}
					}
				}

				//Transform point in image coordinates
				p.x = px;
				p.y = py;
				
				//Displacement vector
				c.x = cx - cxPr;
				c.y = cy - cyPr;
				
				//New interest point location in image
				p.translate(c.transform(U.inverse()));
				px = (int) p.x;
				py = (int) p.y;

				q = calcSecondMomentSqrt(Lxm2smooth, Lxmysmooth, Lym2smooth, new Pixel(cx, cy), Mk);

				float ratio = 1 - q;

				//if ratio == 1 means q == 0 and one axes equals to 0
				if (!Float.isNaN(ratio) && ratio != 1)
				{
					//Update U matrix
					U = U.times(Mk);

					Matrix uVal, uV;
//					EigenvalueDecomposition ueig = U.eig(); 
					EigenValueVectorPair ueig = MatrixUtils.symmetricEig2x2(U);
					uVal = ueig.getD();
					uV = ueig.getV();

					Qinv = normMaxEval(U, uVal, uV);

					//Keypoint doesn't converge
					if (Qinv >= 6) {
						logger.debug("QInverse too large, feature too edge like, affine divergence!");
						divergence = true;
					} else if (ratio <= 0.05) { //Keypoint converges
						convergence = true;

						//Set transformation matrix
						MatrixUtils.zero(transf);
						transf.setMatrix(0, 1, 0, 1, U);
						keypoint.transf = transf.copy();

						ax1 = (float) (1 / Math.abs(uVal.get(1, 1)) * 3 * si);
						ax2 = (float) (1 / Math.abs(uVal.get(0, 0)) * 3 * si);
						phi = Math.atan(uV.get(1, 1) / uV.get(0, 1));
						keypoint.axes = new Point2dImpl(ax1, ax2);
						keypoint.phi = phi;
						keypoint.centre = new Pixel(px, py);
						keypoint.si = si;
						keypoint.size = 2 * 3 * si;

					} else {
						radius = (float) (3 * si * 1.4);
					}
				} else {
					logger.debug("QRatio was close to 0, affine divergence!");
					divergence = true;
				}
			} else {
				logger.debug("Window size has grown too fast, scale divergence!");
				divergence = true;
			}

			++i;
		}
		if(!divergence && !convergence){
			logger.debug("Reached max iterations!");
		}
		return convergence;
	}

	private void displayCurrentPatch(FImage unwarped, float unwarpedx, float unwarpedy, FImage warped, int warpedx, int warpedy, Matrix transform, float scale) {
		DisplayUtilities.createNamedWindow("warpunwarp", "Warped and Unwarped Image",true);
		logger.debug("Displaying patch");
		float resizeScale = 5f;
		float warppedPatchScale = resizeScale ;
		ResizeProcessor patchSizer = new ResizeProcessor(resizeScale);
		FImage warppedPatchGrey = warped.process(patchSizer);
		MBFImage warppedPatch = new MBFImage(warppedPatchGrey.clone(),warppedPatchGrey.clone(),warppedPatchGrey.clone());
		float x = warpedx*warppedPatchScale;
		float y = warpedy*warppedPatchScale;
		float r = scale * warppedPatchScale;
		
		warppedPatch.createRenderer().drawShape(new Ellipse(x,y,r,r,0), RGBColour.RED);
		warppedPatch.createRenderer().drawPoint(new Point2dImpl(x,y), RGBColour.RED,3);
		
		FImage unwarppedPatchGrey = unwarped.clone();
		MBFImage unwarppedPatch = new MBFImage(unwarppedPatchGrey.clone(),unwarppedPatchGrey.clone(),unwarppedPatchGrey.clone());
		unwarppedPatch = unwarppedPatch.process(patchSizer);
		float unwarppedPatchScale = resizeScale;
		
		x = unwarpedx * unwarppedPatchScale ;
		y = unwarpedy * unwarppedPatchScale ;
//		Matrix sm = state.selected.secondMoments;
//		float scale = state.selected.scale * unwarppedPatchScale;
//		Ellipse e = EllipseUtilities.ellipseFromSecondMoments(x, y, sm, scale*2);
		Ellipse e = EllipseUtilities.fromTransformMatrix2x2(transform,x,y,scale*unwarppedPatchScale);
		
		unwarppedPatch.createRenderer().drawShape(e, RGBColour.BLUE);
		unwarppedPatch.createRenderer().drawPoint(new Point2dImpl(x,y), RGBColour.RED,3);
		// give the patch a border (10px, black)
		warppedPatch = warppedPatch.padding(5, 5, RGBColour.BLACK);
		unwarppedPatch = unwarppedPatch.padding(5, 5,RGBColour.BLACK);
		
		MBFImage displayArea = warppedPatch.newInstance(warppedPatch.getWidth()*2, warppedPatch.getHeight());
		displayArea.createRenderer().drawImage(warppedPatch, 0, 0);
		displayArea.createRenderer().drawImage(unwarppedPatch, warppedPatch.getWidth(), 0);
		DisplayUtilities.displayName(displayArea, "warpunwarp");
		logger.debug("Done");	
	}

	/*
	 * Selects the integration scale that maximize LoG in point c
	 */
	float selIntegrationScale(final FImage image, float si, Pixel c) {
		FImage Lap, L;
		int cx = c.x;
		int cy = c.y;
		float maxLap = 0;
		float maxsx = si;
		float sigma, sigma_prev = 0;

		L = image.clone();
		/* 
		 * Search best integration scale between previous and successive layer
		 */
		for (float u = 0.7f; u <= 1.41; u += 0.1)
		{
			float sik = u * si;
			sigma = (float) Math.sqrt(Math.pow(sik, 2) - Math.pow(sigma_prev, 2));

			L.processInline(new FGaussianConvolve(sigma, 3));
			
			sigma_prev = sik;

			Lap = L.process(new FConvolution(LAPLACIAN_KERNEL));

			float lapVal = sik * sik * Math.abs(Lap.pixels[cy][cx]);

			if (u == 0.7)
				maxLap = lapVal;

			if (lapVal >= maxLap)
			{
				maxLap = lapVal;
				maxsx = sik;
			}
		}
		return maxsx;
	}

	/*
	 * Calculates second moments matrix square root
	 */
	float calcSecondMomentSqrt(final FImage dx2, final FImage dxy, final FImage dy2, Pixel p, Matrix Mk)
	{
		Matrix M, V, eigVal, Vinv;

		M = calcSecondMomentMatrix(dx2, dxy, dy2, p);

		/* *
		 * M = V * D * V.inv()
		 * V has eigenvectors as columns
		 * D is a diagonal Matrix with eigenvalues as elements
		 * V.inv() is the inverse of V
		 * */
//		EigenvalueDecomposition meig = M.eig();
		EigenValueVectorPair meig = MatrixUtils.symmetricEig2x2(M);
		eigVal = meig.getD();
		V = meig.getV();
		
//		V = V.transpose();
		Vinv = V.inverse();

		float eval1 = (float) Math.sqrt(eigVal.get(0, 0));
		eigVal.set(0, 0, eval1);
		float eval2 = (float) Math.sqrt(eigVal.get(1, 1));
		eigVal.set(1, 1, eval2);

		//square root of M
		Mk.setMatrix(0, 1, 0, 1, V.times(eigVal).times(Vinv));
		
		//return q isotropic measure
		return Math.min(eval1, eval2) / Math.max(eval1, eval2);
	}

	float normMaxEval(Matrix U, Matrix uVal, Matrix uVec) {
		/* *
		 * Decomposition:
		 * U = V * D * V.inv()
		 * V has eigenvectors as columns
		 * D is a diagonal Matrix with eigenvalues as elements
		 * V.inv() is the inverse of V
		 * */
//		uVec = uVec.transpose();
		Matrix uVinv = uVec.inverse();

		//Normalize min eigenvalue to 1 to expand patch in the direction of min eigenvalue of U.inv()
		double uval1 = uVal.get(0, 0);
		double uval2 = uVal.get(1, 1);

		if (Math.abs(uval1) < Math.abs(uval2))
		{
			uVal.set(0, 0, 1);
			uVal.set(1, 1, uval2 / uval1);

		} else
		{
			uVal.set(1, 1, 1);
			uVal.set(0, 0, uval1 / uval2);
		}

		//U normalized
		U.setMatrix(0,1,0,1,uVec.times(uVal).times(uVinv));

		return (float) (Math.max(Math.abs(uVal.get(0, 0)), Math.abs(uVal.get(1, 1))) / 
				Math.min(Math.abs(uVal.get(0, 0)), Math.abs(uVal.get(1, 1)))); //define the direction of warping
	}

	/*
	 * Selects diffrentiation scale
	 */
	float selDifferentiationScale(final FImage img, FImage Lxm2smooth, FImage Lxmysmooth, FImage Lym2smooth, float si, Pixel c) {
		float s = 0.5f;
		float sdk = s * si;
		float sigma_prev = 0, sigma;

		FImage L, dx2, dxy, dy2;

		double qMax = 0;

		L = img.clone();

		while (s <= 0.751)
		{
			Matrix M;
			float sd = s * si;

			//Smooth previous smoothed image L
			sigma = (float) Math.sqrt(Math.pow(sd, 2) - Math.pow(sigma_prev, 2));

			L.processInline(new FGaussianConvolve(sigma, 3));

			sigma_prev = sd;

			//X and Y derivatives
			FImage Lx = L.process(new FConvolution(DX_KERNEL.multiply(sd)));
			FImage Ly = L.process(new FConvolution(DY_KERNEL.multiply(sd)));		

			FGaussianConvolve gauss = new FGaussianConvolve(si, 3);
			
			FImage Lxm2 = Lx.multiply(Lx);
			dx2 = Lxm2.process(gauss);
			
			FImage Lym2 = Ly.multiply(Ly);
			dy2 = Lym2.process(gauss);

			FImage Lxmy = Lx.multiply(Ly);
			dxy = Lxmy.process(gauss);
			
			M = calcSecondMomentMatrix(dx2, dxy, dy2, new Pixel(c.x, c.y));

			//calc eigenvalues
//			EigenvalueDecomposition meig = M.eig();
			EigenValueVectorPair meig = MatrixUtils.symmetricEig2x2(M);
			Matrix eval = meig.getD();
			double eval1 = Math.abs(eval.get(0, 0));
			double eval2 = Math.abs(eval.get(1, 1));
			double q = Math.min(eval1, eval2) / Math.max(eval1, eval2);

			if (q >= qMax) {
				qMax = q;
				sdk = sd;
				Lxm2smooth.internalAssign(dx2);
				Lxmysmooth.internalAssign(dxy);
				Lym2smooth.internalAssign(dy2);
			}

			s += 0.05;
		}

		return sdk;
	}

//	void calcAffineCovariantRegions(final Matrix image, final vector<KeyPoint> & keypoints,
//			vector<Elliptic_KeyPoint> & affRegions, string detector_type)
//	{
//
//		for (size_t i = 0; i < keypoints.size(); ++i)
//		{
//			KeyPoint kp = keypoints[i];
//			Elliptic_KeyPoint ex(kp.pt, 0, Size_<float> (kp.size / 2, kp.size / 2), kp.size,
//					kp.size / 6);
//
//			if (calcAffineAdaptation(image, ex))        
//				affRegions.push_back(ex);
//
//		}
//		//Erase similar keypoint
//		float maxDiff = 4;
//		Matrix colorimg;
//		for (size_t i = 0; i < affRegions.size(); i++)
//		{
//			Elliptic_KeyPoint kp1 = affRegions[i];
//			for (size_t j = i+1; j < affRegions.size(); j++){
//
//				Elliptic_KeyPoint kp2 = affRegions[j];
//
//				if(norm(kp1.centre-kp2.centre)<=maxDiff){
//					double phi1, phi2;
//					Size axes1, axes2;
//					double si1, si2;
//					phi1 = kp1.phi;
//					phi2 = kp2.phi;
//					axes1 = kp1.axes;
//					axes2 = kp2.axes;
//					si1 = kp1.si;
//					si2 = kp2.si;
//					if(Math.abs(phi1-phi2)<15 && Math.max(si1,si2)/Math.min(si1,si2)<1.4 && axes1.width-axes2.width<5 && axes1.height-axes2.height<5){
//						affRegions.erase(affRegions.begin()+j);
//						j--;                        
//					}
//				}
//			}
//		}
//	}

//	void calcAffineCovariantDescriptors(final Ptr<DescriptorExtractor>& dextractor, final Mat& img,
//			vector<Elliptic_KeyPoint>& affRegions, Mat& descriptors)
//	{
//
//		assert(!affRegions.empty());
//		int size = dextractor->descriptorSize();
//		int type = dextractor->descriptorType();
//		descriptors = Mat(Size(size, affRegions.size()), type);
//		descriptors.setTo(0);
//
//		int i = 0;
//
//		for (vector<Elliptic_KeyPoint>::iterator it = affRegions.begin(); it < affRegions.end(); ++it)
//		{
//			Point p = it->centre;
//
//			Mat_<float> size(2, 1);
//			size(0, 0) = size(1, 0) = it->size;
//
//			//U matrix
//			Matrix transf = it->transf;
//			Mat_<float> U(2, 2);
//			U.setTo(0);
//			Matrix col0 = U.col(0);
//			transf.col(0).copyTo(col0);
//			Matrix col1 = U.col(1);
//			transf.col(1).copyTo(col1);
//
//			float radius = it->size / 2;
//			float si = it->si;
//
//			Size_<float> boundingBox;
//
//			double ac_b2 = determinant(U);
//			boundingBox.width = ceil(U.get(1, 1)/ac_b2  * 3 * si );
//			boundingBox.height = ceil(U.get(0, 0)/ac_b2 * 3 * si );
//
//			//Create window around interest point
//			float half_width = Math.min((float) Math.min(img.width - p.x-1, p.x), boundingBox.width);
//			float half_height = Math.min((float) Math.min(img.height - p.y-1, p.y), boundingBox.height);
//			float roix = max(p.x - (int) boundingBox.width, 0);
//			float roiy = max(p.y - (int) boundingBox.height, 0);
//			Rect roi = Rect(roix, roiy, p.x - roix + half_width+1, p.y - roiy + half_height+1);
//
//			Matrix img_roi = img(roi);
//
//			size(0, 0) = img_roi.width;
//			size(1, 0) = img_roi.height;
//
//			size = U * size;
//
//			Matrix transfImgRoi, transfImg;
//			warpAffine(img_roi, transfImgRoi, transf, Size(ceil(size(0, 0)), ceil(size(1, 0))),
//					INTER_AREA, BORDER_DEFAULT);
//
//
//			Mat_<float> c(2, 1); //Transformed point
//			Mat_<float> pt(2, 1); //Image point
//			//Point within the Roi
//			pt(0, 0) = p.x - roix;
//			pt(1, 0) = p.y - roiy;
//
//			//Point in U-Normalized coordinates
//			c = U * pt;
//			float cx = c(0, 0);
//			float cy = c(1, 0);
//
//
//			//Cut around point to have patch of 2*keypoint->size
//
//			roix = Math.max(cx - radius, 0.f);
//			roiy = Math.max(cy - radius, 0.f);
//
//			roi = Rect(roix, roiy, Math.min(cx - roix + radius, size(0, 0)),
//					Math.min(cy - roiy + radius, size(1, 0)));
//			transfImg = transfImgRoi(roi);
//
//			cx = c(0, 0) - roix;
//			cy = c(1, 0) - roiy;
//
//			Matrix tmpDesc;
//			KeyPoint kp(Point(cx, cy), it->size);
//
//			vector<KeyPoint> k(1, kp);
//
//			transfImg.convertTo(transfImg, CV_8U);
//			dextractor->compute(transfImg, k, tmpDesc);
//
//			for (int j = 0; j < tmpDesc.width; j++)
//			{
//				descriptors.get(i, j) = tmpDesc.get(0, j);
//			}
//
//			i++;
//
//		}
//
//	}
	
	public static void main(String[] args) throws IOException {
		float sd = 2;
		float si = 1.4f * sd;
		HarrisIPD ipd = new HarrisIPD(sd, si);
		FImage img = ImageUtilities.readF(AffineAdaption.class.getResourceAsStream("/org/openimaj/image/data/sinaface.jpg"));
		
//		img = img.multiply(255f);
		
		ipd.findInterestPoints(img);
		List<InterestPointData> a = ipd.getInterestPoints(1F/(256*256));
		
		System.out.println("Found " + a.size() + " features");
		
		AffineAdaption adapt = new AffineAdaption();
		EllipticKeyPoint kpt = new EllipticKeyPoint();
		MBFImage outImg = new MBFImage(img.clone(),img.clone(),img.clone());
		for (InterestPointData d : a) {
			
//			InterestPointData d = new InterestPointData();
//			d.x = 102;
//			d.y = 396;
			logger.info("Keypoint at: " + d.x + ", " + d.y);
			kpt.si = si;
			kpt.centre = new Pixel(d.x, d.y);
			kpt.size = 2 * 3 * kpt.si;
			
			boolean converge = adapt.calcAffineAdaptation(img, kpt);
			if(converge)
			{
				outImg.drawShape(new Ellipse(kpt.centre.x,kpt.centre.y,kpt.axes.getX(),kpt.axes.getY(),kpt.phi), RGBColour.BLUE);
				outImg.drawPoint(kpt.centre, RGBColour.RED,3);
			}
			
			
			
			logger.info("... converged: "+ converge);
		}
		DisplayUtilities.display(outImg);
	}
}

