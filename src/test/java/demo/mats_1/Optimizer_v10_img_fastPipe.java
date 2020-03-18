package demo.mats_1;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Plot;
import ij.plugin.ChannelSplitter;
import net.imglib2.img.VirtualStackAdapter;
import net.imglib2.type.numeric.ARGBType;
import org.apache.commons.math3.stat.descriptive.rank.Max;
import org.scijava.plugin.Parameter;

import java.util.Arrays;
import java.util.Random;

//import net.imglib2.neighborsearch.

public class Optimizer_v10_img_fastPipe {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Inputs
    int max_steps = 50; // max steps of iteration
    int used_steps;

    int N_evaluations = 0; // count how many times the likelihood was evaluated (most time consuming step...)

    // DECLARE YOUR INPUT
    int Nparams =   5;  // number of parameters
    int NLifePoints = 10; // number of life points

    /*
    Performance measurement results:

    #################################################
     int max_steps = 4000; // max steps of iteration
     int NLifePoints = 100; // number of life points

     ****************************************************************************************
    ** Finished!
    Total Time [sec]:	3540.321
    Acceptance Rate:	0.08577230079820754
    Replacments:		1225
    Total Samples:	14282
    ****************************************************************************************
    ** Parameters:
    18.03981	 41.68622	 9.493754	 0.2040106	 9.6441

    #################################################
     int max_steps = 100; // max steps of iteration
     int NLifePoints = 20; // number of life points

    ****************************************************************************************
    ** Finished!
    Total Time [sec]:	29.942
    Acceptance Rate:	0.5
    Replacments:		99
    Total Samples:	198
    ****************************************************************************************
    ** Parameters:
    27.086956	 42.14374	 1.3973367	 1.6266418	 18.899311

    #################################################
     int max_steps = 50; // max steps of iteration
     int NLifePoints = 10; // number of life points

    ****************************************************************************************
    ** Finished!
    Total Time [sec]:	15.397
    Acceptance Rate:	0.4666666666666667
    Replacments:		49
    Total Samples:	105
    ****************************************************************************************
    ** Parameters:
    13.898545	 39.573277	 5.02139	 7.9545164	 12.478712

     */

    float[][] cube =  {
            //{0.1f,5},   // Cannylow
            //{0.1f,5},      // Cannyhigh
            {0,100},    // gaussian_radius
            {0,50},     // Threshold_high
            {0,10},   // minParticle_white
            {0,10},   // minParticle_black

            {0,40}     // sigma # error of the parameter estimation
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Loading Images etc.
    @Parameter
    private ImagePlus input_image;
    private ImagePlus manual_segmented_image; // msi

    //
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Initialise the imaging pipeline
    Pipeline_fMN_fast pipe_fast;

    float[][] manual_segmentation_contour;
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    float[][] LifePoints_Positions = new float[NLifePoints][Nparams];   // array of parameter sets
    double[] LifePoints_Likelyhood = new double[NLifePoints];       // corresponding Likelihoods to the array of parameter sets

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // For the Bayesian post_equal_weights.dat
    float[][] LifePoints_Positions_Register = new float[max_steps+NLifePoints][Nparams];   // This one will be used to register the parameter values that where discarded. It is needed to calculate the post_equal_weights.dat
    double[] LifePoints_Likelyhood_Register = new double[max_steps+NLifePoints];       // This one will register the discarded likelihoods.

    /**
     * To obtain equally-weighted posterior representatives, all we need do is accept point θ i
     * with probability p i /K, where K ≥ max j (p j ) must be large enough to avoid duplication. The
     * maximum number of representative posterior samples is given by the entropy (i.e. the Shannon
     * channel capacity) as
	 * <p>
	 * This one will be used to directly caclulate the post_equal_weight.dat
     */
    double[] p_i    = new double[max_steps+NLifePoints];


    public void run(ImagePlus input_img_org, ImagePlus input_img_msi_org) {
         // render the input image to 8-bit once.
        input_image =input_img_org.duplicate();
        manual_segmented_image =input_img_msi_org.duplicate();

        IJ.run(input_image, "8-bit", "");

        ImagePlus[] channels = ChannelSplitter.split(input_image);
        input_image = channels[0];
        IJ.run(input_image, "Grays", "");

        ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // Prepare the image.

        pipe_fast = new Pipeline_fMN_fast(input_image);

        //get contour image
        ContourUtilies pipe_contour = new ContourUtilies();
        //get pixels from contour image
        manual_segmentation_contour = pipe_contour.getContourPixels(manual_segmented_image);

        ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // Initialise Bayes.

        Random random = new Random(); // Initalise random number generator

        // Nested Sampling parameters:
        /*Check out: Skilling J. 2006. Nested Sampling for General Bayesian Computation. Bayesian Analysis - International Society for Bayesian Analysis 1:833–860. */
        double Z_NS = 0;    // Z_NS represents the Bayesian evidence
        double Z_NS_lp = 0;    // The part of the evidence that is still within the live points.
        double X_NS = 1;    // X_NS is needed to calculated the weights of the individual nested steps
        double w_NS = 0;    // Nested Sampling intrinsic weights.
        // Inital Parameter sets.
        for (int i_lp = 0; i_lp < LifePoints_Positions.length; i_lp ++) {
            for (int i_para = 0; i_para < Nparams; i_para ++) {
                // Roll the dice to generate random parameter sets for the initial cloud points.
                LifePoints_Positions[i_lp][i_para] = random.nextFloat()*(cube[i_para][1]-cube[i_para][0])+cube[i_para][0] ;
            }
            // Evalutate the Likelihoods for the parameter sets of the LivePoints.
            LifePoints_Likelyhood[i_lp] = Likelihood(LifePoints_Positions[i_lp]);

        }
        ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        //
        long tstart = System.currentTimeMillis();
        // Now start the actual loop to replace the worst lifepoints stepwise.
        for (int s = 0; s < max_steps; s++) {
            // find the worst point
            int index = argMin(LifePoints_Likelyhood);
            double worstLikelihood = LifePoints_Likelyhood[index];

            // generate some output
            for (int i_para = 0; i_para < Nparams; i_para ++) {
                // Roll the dice to generate random parameter sets for the initial cloud points;
                System.out.print(LifePoints_Positions[index][i_para]);
                System.out.print("\t ");
            }
            System.out.print(worstLikelihood);
            System.out.print("\t ");
            System.out.print(N_evaluations);
            System.out.print("\t ");
            System.out.print(System.currentTimeMillis()-tstart);
            System.out.print("\t ");
            System.out.print("*");
            System.out.print("\t ");
            System.out.print(Z_NS);
            System.out.print("\t ");
            System.out.print(Z_NS_lp);


            /*
            Mats: Careful! I think this has to be done individually for each parameter to respect the internal metrics of
            the prior space.
            Also Mats -  a little later: Apparently this seems to work.
             */

            // get R distances according to RadFriends algorithm. - DOI 10.1007/s11222-014-9512-y
            float[] R_dists = calc_R(LifePoints_Positions);

            // Create an empty test parameter point.
            float[] test_para = new float[Nparams];
            double LH_test = -99999; // Just give a super bad Likelihood, this will be overwritten anyways.

            int found2 = 0; // found2 : Is the Likelihood of the new point better than the likelihood of the previous worst point.(Than replace the previous worst point.)
            while (found2 == 0) {
                // step 1 is to find a parameter set that satisfies the distances measurement requirement.
                for (int i_para = 0; i_para < Nparams; i_para++) {
                    int found1 = 0; // found1 : Is the Parameter set within the given R distance.
                    while (found1 == 0) {
                        // roll  the dice for a new parameter set
                        test_para[i_para] = random.nextFloat()*(cube[i_para][1]-cube[i_para][0])+cube[i_para][0];
                        // check dimensionwise whether this parameter is within the given distance.
                        found1 = checkR4para(i_para, R_dists[i_para], test_para[i_para], LifePoints_Positions);
                    }
                }
                // step two evaluate the likelihood.
                N_evaluations += 1;
                LH_test = Likelihood(test_para);
                if (LH_test >= worstLikelihood) {
                    found2 = 1;
                }
            }

            // replace worst point
            for (int i_para = 0; i_para < Nparams; i_para++) {
                LifePoints_Positions_Register[s][i_para] = LifePoints_Positions[index][i_para];
                LifePoints_Positions[index][i_para] = test_para[i_para];

            }
            LifePoints_Likelyhood_Register[s] = LifePoints_Likelyhood[index];
            LifePoints_Likelyhood[index] = LH_test;

            double X_NS_previous = X_NS;
            X_NS = Math.exp(-1*((double)(s+1)/LifePoints_Likelyhood.length));
            w_NS = X_NS_previous - X_NS;
            Z_NS += (w_NS * LH_test);

            Z_NS_lp = 0;
            for (int i_lp = 0; i_lp < LifePoints_Likelyhood.length; i_lp++) {
                Z_NS_lp += LifePoints_Likelyhood[i_lp] * w_NS;
            }

			// TODO: @Mats @Peter
            //  p_i[s] = w_NS * LH_test; // this value has to be devided by the final value of Z


            System.out.print("\t ");
            System.out.print(LH_test);
            System.out.print("\t ");
            System.out.print(w_NS);
            System.out.print("\n");


            // Termination criterion. In accordance to:
            /*
            doi:10.1111/j.1365-2966.2007.12353.x

            " [...] We choose to stop when this quantity would no longer change the final evidence estimate by some user-defined value (we use 0.1 in log-evidence). [...] "
             */
            used_steps = s;
            if((Math.abs(Z_NS_lp)<=0.1) & (s>50)){
                break;
            }

        }

        /* For the scratch assay I(MLM) showed that the discontinuity is crucial. Therefore, Posterior distributions are hard to estimate correctly.
        Therefore, I use only the live_point with the highest log-LH.

        However, weights and log-LH are all calculated by the program. So this could be implemented in future. */

        // find highest log_LH:
        int index = argMax(LifePoints_Likelyhood);

        // generate some output
        System.out.print("****************************************************************************************\n");
        System.out.print("** Finished!\n");
        System.out.print("Total Time [sec]:\t");
        System.out.print((System.currentTimeMillis()-tstart)*1./1000);
        System.out.print("\n");
        System.out.print("Acceptance Rate:\t");
        System.out.print(used_steps*1./N_evaluations);
        System.out.print("\n");
        System.out.print("Replacments:\t\t");
        if (used_steps == max_steps) {
            System.out.print(max_steps);
            System.out.print(" * early");
        }
        else{System.out.print(used_steps);}
        System.out.print("\n");
        System.out.print("Total Samples:\t");
        System.out.print(N_evaluations);
        System.out.print("\n");

        System.out.print("****************************************************************************************\n");
        System.out.print("** Parameters:\n");

        for (int i_para = 0; i_para < Nparams; i_para ++) {
            // Roll the dice to generate random parameter sets for the initial cloud points;
            System.out.print(LifePoints_Positions[index][i_para]);
            System.out.print("\t ");
        }

        //sleep(1000);
        ImagePlus pgi = pipe_fast.exec(LifePoints_Positions[index]); // pipeline generated image
        //sleep(10000);

        showComposite(pgi);
    }

    private void showComposite(ImagePlus pgi) {
        BdvStackSource< ? > handle = BdvFunctions.show(VirtualStackAdapter.wrap(
                input_image), input_image.getTitle(), BdvOptions.options().is2D());
        BdvFunctions.show(VirtualStackAdapter.wrap(pgi), input_image.getTitle(), BdvOptions.options().addTo(handle.getBdvHandle())).setColor(new ARGBType(0x770000));
    }

    private void sleep(int i) {
        try {
            Thread.sleep(i);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private double[] inArray(double[] values, int[] indices) {
        double[] result = new double[indices.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = values[indices[i]];
        }
        return result;
    }

    private int argMin(double[] array) {
        double minimum = 0;
        int argmin = 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i] < minimum || i == 0) {
                minimum = array[i];
                argmin = i;
            }
        }
        return argmin;
    }

    private int argMax(double[] array) {
        double maximum = 0;
        int argmax = 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i] > maximum || i == 0) {
                maximum = array[i];
                argmax = i;
            }
        }
        return argmax;
    }

    private int checkR4para(int i_para, float R, float test_para,  float[][] lifePointPositions) {
        int[] positions = new int[lifePointPositions.length];

        int check=0;
        for (int i_lp = 0; i_lp < lifePointPositions.length; i_lp++) {
            float dist = Math.abs(lifePointPositions[i_lp][i_para] - test_para);
            if (dist <= R){check = check + 1;}
        }

        if (check > 0){return 1;}
        else {return 0;}
    }

    private float[] calc_R(float[][] lifePointPositions) {
        float[] Rdist = new float[Nparams];
        for (int i_para = 0; i_para < Nparams; i_para ++) {
            float[] positions = new float[lifePointPositions.length];
            for (int i_lp = 0; i_lp < lifePointPositions.length; i_lp++) {
                positions[i_lp] = lifePointPositions[i_lp][i_para];
            }
            Arrays.sort(positions);

            double[] nearestNeighborDistance = new double[positions.length];
            for (int i = 0; i < nearestNeighborDistance.length; i++) {
                if (i == 0) {
                    nearestNeighborDistance[i] = Math.abs(positions[i] - positions[i + 1]);
                } else if (i == nearestNeighborDistance.length - 1) {
                    nearestNeighborDistance[i] = Math.abs(positions[i] - positions[i - 1]);
                } else {
                    nearestNeighborDistance[i] = Math.min(  Math.abs(positions[i] - positions[i - 1]),
                            Math.abs(positions[i] - positions[i + 1]));
                }
            }

            double Rmax = new Max().evaluate(nearestNeighborDistance) * 1.25;
            double r = (Rmax / 2); // nomenclature from RadFriends
            Rdist[i_para] = (float)(r);
        }
        return Rdist;
    }

    private Plot plot(double[] xAxis, double[] yAxis) {
        Plot plot = new Plot("title", "xaxis", "yaxis");
        plot.add("curve", xAxis, yAxis);
        //plot.show();
        return plot;
    }

    private double Likelihood(float[] param) {
        float p1   = param[0];
        float p2   = param[1];
        float p3   = param[2];
        float p4   = param[3];
        //float p5   = param[4];
        //float p6   = param[5];
        float sigma  = param[4];
        float[] xx_msi= manual_segmentation_contour[0];
        float[] yy_msi= manual_segmentation_contour[1];

        double logLH = 0;
        // now apply the pipeline to an image
        float[] parameterset = {p1,p2,p3,p4};//,p5,p6};
        ImagePlus pgi = pipe_fast.exec(parameterset); // pipeline generated image

        // PERHAPS ONE HAS TO TURN THIS ON. NOT SURE!
        //get contour image

        ContourUtilies pipe_contour = new ContourUtilies();


        //get pixels from contour image
        float[][] pgi_contour = pipe_contour.getContourPixels(pgi);
        float[] xx_pgi= pgi_contour[0];
        float[] yy_pgi= pgi_contour[1];
        IJ.run(input_image, "8-bit", "");
        // Check that the length of contour of the pipeline generate image is not zero:
        if (xx_pgi.length > 10){


            //prepare everything for the nearest neighbour query:

            float[] arx1;
            float[] ary1;
            float[] arx2;
            float[] ary2;

            if (xx_pgi.length >= xx_msi.length) {
                arx1 = xx_pgi;
                ary1 = yy_pgi;
                arx2 = xx_msi;
                ary2 = yy_msi;
            } else {
                arx1 = xx_msi;
                ary1 = yy_msi;
                arx2 = xx_pgi;
                ary2 = yy_pgi;
            }

            PointSet kdtree = new PointSet(arx2, ary2);

            for (int i = 0; i < arx1.length; i = i + 1) {
                float[] testpoint = new float[]{arx1[i], ary1[i]};

                float dist = kdtree.distanceTo(testpoint[0], testpoint[1]); // this comes down to data - model

                logLH += -0.5 * Math.log(2 * Math.PI * Math.pow(sigma, 2)) + -0.5 * (Math.pow(dist, 2) / Math.pow(sigma, 2));

            }
        } else { // in case the parameters make an all black or all white image. ... so if no contours are present, just punish this very bad
            for (int i = 0; i < xx_msi.length; i = i + 1) {
                float dist = input_image.getWidth()+ input_image.getHeight(); // a distance bigger than any distance that could occur in the image.
                logLH += -0.5 * Math.log(2 * Math.PI * Math.pow(sigma, 2)) + -0.5 * (Math.pow(dist, 2) / (Math.pow(sigma, 2)));
            }
        }

        return logLH;
    }
}


