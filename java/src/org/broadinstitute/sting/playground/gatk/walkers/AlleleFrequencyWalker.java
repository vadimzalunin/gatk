package org.broadinstitute.sting.playground.gatk.walkers;
//import org.broadinstitute.sting.gatk.iterators.LocusIterator;
import org.broadinstitute.sting.gatk.LocusContext;
import org.broadinstitute.sting.gatk.refdata.ReferenceOrderedDatum;
import org.broadinstitute.sting.gatk.refdata.rodDbSNP;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.LocusWalker;
import org.broadinstitute.sting.playground.utils.AlleleFrequencyEstimate;
import org.broadinstitute.sting.playground.utils.AlleleMetrics;
import org.broadinstitute.sting.utils.cmdLine.Argument;
import org.broadinstitute.sting.utils.*;
import org.apache.log4j.Logger;
import net.sf.samtools.SAMRecord;

import java.util.List;
import java.util.Arrays;
import java.util.Random;
import java.io.PrintStream;

public class AlleleFrequencyWalker extends LocusWalker<AlleleFrequencyEstimate, String>// implements AllelicVariant
{
    @Argument(doc="Number of chromosomes in data") public int    N;
    @Argument(required=false,defaultValue="0") public int DOWNSAMPLE;
    @Argument(doc="File to output GFF formatted allele frequency calls") public String GFF_OUTPUT_FILE;
    @Argument(shortName="met", doc="Turns on logging of metrics on the fly with AlleleFrequency calculation") public boolean LOG_METRICS;
    @Argument(required=false, defaultValue="metrics.out", doc="Name of file where metrics will output") public String METRICS_OUTPUT_FILE;
    @Argument(required=false, doc="Ignores 4-base probabilities and only uses the quality of the best/called base") public static boolean FORCE_1BASE_PROBS;

    protected static Logger logger = Logger.getLogger(AlleleFrequencyWalker.class);

    Random random;
    PrintStream output;

    public boolean requiresReads()     { return true; }    

    public AlleleFrequencyEstimate map(RefMetaDataTracker tracker, char ref, LocusContext context) 
    {
        // Convert context data into bases and 4-base quals
        String bases = getBases(context);
        double quals[][] = getQuals(context);

        /*
        // DEBUG: print the data for a read 
        {
            List<SAMRecord> reads = context.getReads();
            for (int i = 0; i < reads.size(); i++)
            {
                String cigar = reads.get(i).getCigarString();
                System.out.println("DEBUG " + cigar);
            }
        }
        */

        logger.debug(String.format("In alleleFrequnecy walker: N=%d, d=%d", N, DOWNSAMPLE));

        if ((DOWNSAMPLE != 0) && (DOWNSAMPLE < bases.length()))
        {
            String downsampled_bases     = "";
            double downsampled_quals[][] = new double[DOWNSAMPLE][4];
           
            int picked_bases[] = new int[bases.length()];
            for (int i = 0; i < picked_bases.length; i++) { picked_bases[i] = 0; }
            while (downsampled_bases.length() < DOWNSAMPLE)
            {
                int choice;
                for (choice = random.nextInt(bases.length()); picked_bases[choice] == 1; choice = random.nextInt(bases.length()));
                picked_bases[choice] = 1;
                downsampled_bases += bases.charAt(choice);
                downsampled_quals[downsampled_bases.length()-1] = quals[choice];
            } 

            //System.out.printf("From: %s\n", bases);
            //System.out.printf("To  : %s\n", downsampled_bases);

            bases = downsampled_bases;
            quals = downsampled_quals;
        }

        // Count bases
        int[] base_counts = new int[4];
        for (byte b : bases.getBytes())
            base_counts[nuc2num[b]]++;

        // Find alternate allele - 2nd most frequent non-ref allele
        // (maybe we should check for ties and eval both or check most common including quality scores)
        int altnum = -1; // start with first base numerical identity (0,1,2,3)
        double altcount = -1; // start with first base count
        int refnum = nuc2num[ref];

        for (int b=0; b<4; b++) {
            if ((b != refnum) && (base_counts[b] > altcount)) {
                altnum = b; altcount = base_counts[b];
            }
        }
        assert(altnum != -1);

        AlleleFrequencyEstimate alleleFreq = AlleleFrequencyEstimator(context.getLocation(), N, bases.getBytes(), quals, refnum, altnum, bases.length());

        alleleFreq.notes = String.format("A:%d C:%d G:%d T:%d", 
                                            base_counts[nuc2num['A']],
                                            base_counts[nuc2num['C']],
                                            base_counts[nuc2num['G']],
                                            base_counts[nuc2num['T']]);

        // Print dbSNP data if its there
        if (true) {
            for ( ReferenceOrderedDatum datum : tracker.getAllRods() ) {
                if ( datum != null && datum instanceof rodDbSNP) {
                    rodDbSNP dbsnp = (rodDbSNP)datum;
                    //System.out.printf("  DBSNP %s on %s => %s%n", dbsnp.toSimpleString(), dbsnp.strand, Utils.join("/", dbsnp.getAllelesFWD()));
                    alleleFreq.notes += String.format(" ROD: %s ",dbsnp.toString());
                }
            }
        }

        logger.debug(String.format(" => result is %s", alleleFreq));

        if (LOG_METRICS) metrics.nextPosition(alleleFreq, tracker);

        return alleleFreq;
    }

    static public String getBases (LocusContext context)
    {
        // Convert bases to CharArray
        int numReads = context.getReads().size(); //numReads();
        char[] bases = new char[numReads];
        //int refnum = nuc2num[ref];

        List<SAMRecord> reads = context.getReads();
        List<Integer> offsets = context.getOffsets();
        for (int i =0; i < numReads; i++ ) {
            SAMRecord read = reads.get(i);
            int offset = offsets.get(i);
            bases[i] = read.getReadString().charAt(offset);
        }
        return new String(bases);
    }

    static public double[][] getQuals (LocusContext context)
    {
        int numReads = context.getReads().size(); //numReads();
        double[][] quals = new double[numReads][4];

        List<SAMRecord> reads = context.getReads();
        List<Integer> offsets = context.getOffsets();
        for (int i =0; i < numReads; i++ ) {
            SAMRecord read = reads.get(i);
            int offset = offsets.get(i);

            // First, set the called base to the correct quality
            int callednum = nuc2num[read.getReadBases()[offset]]; // number of the called base (0,1,2,3)

            quals[i][callednum] = 1 - Math.pow(10.0, (double)read.getBaseQualities()[offset] / -10.0);

            // For other 3 bases, check if 2-base probs exist
            assert (reads.size() > 0);
            Object SQ_field = reads.get(i).getAttribute("SQ");
            if (SQ_field == null || FORCE_1BASE_PROBS) {
                // Set all nonref qual scores to their share of the remaining probality not "used" by the reference base's qual
                double nonref_quals = (1.0 - quals[i][callednum]) / 3;
                for (int b=0; b<4; b++)
                    if (b != callednum)
                        quals[i][b] = nonref_quals;
            }else{
                assert (SQ_field instanceof byte[]);
                byte[] hex_quals = (byte[]) SQ_field;
                //System.out.printf("SQ field (hex): %s\n", bytesToHexString(hex_quals));
                //System.out.printf("SAM record: %s\n", read.format());

                byte hex_qual = hex_quals[offset];
                int baseIndex = QualityUtils.compressedQualityToBaseIndex(hex_qual);
                // Todo: Andrew indexes his nucleotides as 0:A, 1:C, 2:T, 3:G.  Kiran indexes his nucleotides as 0:A, 1:C, 2:G, 3:T.  This must be reconciled.  Here, I just translate appropriately.
                int called2num = 0;
                switch (baseIndex) {
                    case 0: called2num = 0; break;
                    case 1: called2num = 1; break;
                    case 2: called2num = 3; break;
                    case 3: called2num = 2; break;
                    default: assert(baseIndex >= 0 && baseIndex < 4); break;
                }

                double qual2 = QualityUtils.compressedQualityToProb(hex_qual);
                //System.out.printf("2ND %x %d %f\n", hex_qual, called2num, qual2);
                quals[i][called2num] = qual2;

                double nonref_quals = (1.0 - quals[i][callednum] - quals[i][called2num]) / 2;
                for (int b=0; b<4; b++)
                    if (b != callednum && b != called2num)
                        quals[i][b] = nonref_quals;
            }
        }

        //print_base_qual_matrix(quals);
        return quals;
    }

    // Code pulled from SAMUtils for debugging 
    static String bytesToHexString(final byte[] data) {
        final char[] chars = new char[2 * data.length];
        for (int i = 0; i < data.length; i++) {
            final byte b = data[i];
            chars[2*i] = toHexDigit((b >> 4) & 0xF);
            chars[2*i+1] = toHexDigit(b & 0xF);
        }
        return new String(chars);
    }

    private static char toHexDigit(final int value) {
        return (char) ((value < 10) ? ('0' + value) : ('A' + value - 10));
    }

    public AlleleFrequencyEstimate AlleleFrequencyEstimator(GenomeLoc location, int N, byte[] bases, double[][] quals, int refnum, int altnum, int depth)
    {

        // q = hypothetical %nonref
        // qstar = true %nonref
        // G = N, qstar, alt
        // N = number of chromosomes
        // alt = alternate hypothesis base (most frequent nonref base)
        // ref = reference base

        // b = number of bases at locus

        double epsilon = 0; //  1e-2;
        double qstar;
        int qstar_N;

        double qstep = 0.001;
        double qend = 1.0 + qstep / 10; // makes sure we get to 1.0 even with rounding error of qsteps accumulating

        // Initialize empyt MixtureLikelihood holders for each possible qstar (N+1)
        MixtureLikelihood[] bestMixtures = new MixtureLikelihood[N+1];
        // Fill 1..N ML positions with 0 valued MLs
        for (int i=1; i<=N; i++) bestMixtures[i] = new MixtureLikelihood();
        // Calculate null hypothesis for qstar = 0, qhat = 0
        double posterior_null_hyp = P_D_q(bases, quals, 0.0, refnum, altnum) + P_q_G(bases, N, 0.0, epsilon, 0) + P_G(N, 0);
        // Put null hypothesis into ML[0] because that is our prob of that theory and we don't loop over it below
        bestMixtures[0] = new MixtureLikelihood(posterior_null_hyp, 0.0, 0.0);

        // hypothetic allele balance that we sample over
        for (double q=0.0; q <= qend; q += qstep) 
        {
            long q_R = Math.round(q*bases.length);
            double pDq = P_D_q(bases, quals, q, refnum, altnum);

            // qstar - true allele balances
            for (qstar = epsilon + ((1.0 - 2*epsilon)/N), qstar_N = 1; qstar <= 1.0; qstar += (1.0 - 2*epsilon)/N, qstar_N++) 
            { 
                double pqG = P_q_G(bases, N, q, qstar, q_R);
                double pG  = P_G(N, qstar_N); 
                double posterior = pDq + pqG + pG;

                if (posterior > bestMixtures[qstar_N].posterior)
                    bestMixtures[qstar_N] = new MixtureLikelihood(posterior, qstar, q);

                //System.out.format("%.2f %.2f %5.2f %5.2f %5.2f %5.2f\n", q, qstar, pDq, pqG, pG, posterior);
            }
        }

        // First reverse sort NONREF mixtures according to highest posterior probabililty
        Arrays.sort(bestMixtures, 1, N+1);

        // Calculate Lod of any variant call versus the reference call
        // Answers how confident are we in the best variant (nonref) mixture versus the null hypothesis
        // reference mixture - qhat = qstar = 0.0
        double lodVarVsRef = bestMixtures[1].posterior - posterior_null_hyp;

        // Now reverse sort ALL mixtures according to highest posterior probability
        Arrays.sort(bestMixtures);

        // Calculate Lod of the mixture versus other possible
        // Answers how confident are we in the best mixture versus the nextPosition best mixture
        double lodBestVsNextBest = bestMixtures[0].posterior - bestMixtures[1].posterior;

        AlleleFrequencyEstimate alleleFreq = new AlleleFrequencyEstimate(location,
                                                                         num2nuc[refnum], 
                                                                         num2nuc[altnum],
                                                                         N, 
                                                                         bestMixtures[0].qhat,
                                                                         bestMixtures[0].qstar,
                                                                         lodVarVsRef,
                                                                         lodBestVsNextBest,
                                                                         depth);
        return alleleFreq;
    }

    public class MixtureLikelihood implements Comparable<MixtureLikelihood> {
        public double posterior;
        public double qstar;
        public double qhat;

        MixtureLikelihood() {
            this.posterior = Math.log10(0);
            this.qstar = Math.log10(0);
            this.qhat = Math.log10(0);
        }

        MixtureLikelihood(double posterior, double qstar, double qhat) {
            this.posterior = posterior;
            this.qstar = qstar;
            this.qhat = qhat;
        }

        public int compareTo (MixtureLikelihood other) {
            // default sorting is REVERSE sorting on posterior prob; gives array with top post. prob. as first element
            if (posterior < other.posterior)      return 1;
            else if (posterior > other.posterior) return -1;
            else                                  return 0;
        }
    }

    static double P_D_q(byte[] bases, double[][]quals, double q, int refnum, int altnum) 
    {
        double p = 0.0;

        for (int i=0; i<bases.length; i++) 
        {
            p += Math.log10((1-q) * quals[i][refnum] + q * quals[i][altnum]);
        }

        return p;
    }

    static double P_q_G(byte [] bases, int N, double q, double qstar, long q_R) 
    {
        if (N != 2) { return 0.0; }
        else { return Math.log10(binomialProb(q_R, bases.length, qstar)); }
    }

    static double P_G(int N, int qstar_N) 
    {
        // badly hard coded right now.
        if      (qstar_N == 0) { return Math.log10(0.999); }
        else if (qstar_N == N) { return Math.log10(1e-5);  }
        else                   { return Math.log10(1e-3);  }
    }

    static String genotypeTypeString(double q, int N){
        switch (Math.round((float)q*N)) {
            case (0):
                return "ref";
            case (1):
                return "het";
            case (2):
                return "hom";
        }
        return "ERROR";
    }

    static double binomialProb(long k, long n, double p) {
        // k - number of successes
        // n - number of Bernoulli trials
        // p - probability of success

        if ((n*p < 5) && (n*(1-p) < 5))
        {
            // For small n and the edges, compute it directly.
            return (double)nchoosek(n, k) * Math.pow(p, k) * Math.pow(1-p, n-k);
        }
        else
        {
            // For large n, approximate with a gaussian.
            double mean  = (double)(n*p);
            double var   = Math.sqrt((double)(n*p)*(1.0-p));
            double ans   = (double)(1.0 / (var*Math.sqrt(2*Math.PI)))*Math.exp(-1.0 * Math.pow((double)k-mean,2)/(2.0*var*var));
            double check = (double)nchoosek(n, k) * Math.pow(p, k) * Math.pow(1-p, n-k);
            double residual = ans - check;

            //System.out.format("DBG: %d %.10f %.10f\n", nchoosek(n, k), Math.pow(p, k), Math.pow(1-p, n-k));
            //System.out.format("RESIDUAL: (%d,%d,%f) %.10f %.10f %.10f\n", k, n, p, ans, check, residual);

            return check;
        }
    }

    static long nchoosek(long n, long k) {
        long m = n - k;
        if (k < m)
            k = m;

        long t = 1;
        for (long i = n, j = 1; i > k; i--, j++)
            t = t * i / j;
        return t;
    }

    public static String repeat(char letter, long count) {
        // Repeat a character count times
        String result = "";
        for (int i=0; i<count; i++) {
            result = result + letter;
        }
        return result;
    }

    private String  confident_ref_interval_contig  = "";
    private long    confident_ref_interval_start   = 0;
    private double  confident_ref_interval_LOD_sum = 0;
    private double  confident_ref_interval_length  = 0;
    private long    last_position_considered       = -1;
    private boolean inside_confident_ref_interval  = false;
    AlleleMetrics   metrics;    

    public String reduceInit() 
    { 
        confident_ref_interval_contig  = "";
        confident_ref_interval_start   = 0;
        confident_ref_interval_LOD_sum = 0;
        confident_ref_interval_length  = 0;
        last_position_considered       = -1;
        inside_confident_ref_interval  = false;
        if (LOG_METRICS) metrics = new AlleleMetrics(METRICS_OUTPUT_FILE);
        return "";
    }

    public String reduce(AlleleFrequencyEstimate alleleFreq, String sum) 
    {
        // Print RESULT data for confident calls

       long current_offset = alleleFreq.location.getStart(); //Integer.parseInt(tokens[1]);

        if (inside_confident_ref_interval &&
                ((alleleFreq.lodVsRef > -5.0) || (current_offset != last_position_considered + 1)) )
        {
            // No longer hom-ref, so output a ref line.
            double lod = confident_ref_interval_LOD_sum / confident_ref_interval_length;

            output.format("%s\tCALLER\tREFERENCE\t%d\t%d\t%f\t.\t.\tLENGTH %d\n",
                            confident_ref_interval_contig,
                            confident_ref_interval_start,
                            last_position_considered,
                            lod,
                            (int)(confident_ref_interval_length));

            inside_confident_ref_interval = false;
        }
        else if (inside_confident_ref_interval && (alleleFreq.lodVsRef <= -5.0))
        {
            // Still hom-ref so increment the counters.
            confident_ref_interval_LOD_sum += alleleFreq.lodVsRef;
            confident_ref_interval_length  += 1;
        }
        else if ((!inside_confident_ref_interval) && (alleleFreq.lodVsRef > -5.0))
        {
            // do nothing.
        }
        else if ((!inside_confident_ref_interval) && (alleleFreq.lodVsRef <= -5.0))
        {
            // We moved into a hom-ref region so start a new interval.
            confident_ref_interval_contig  = alleleFreq.location.getContig();
            confident_ref_interval_start   = alleleFreq.location.getStart();
            confident_ref_interval_LOD_sum = alleleFreq.lodVsRef;
            confident_ref_interval_length  = 1;
            inside_confident_ref_interval  = true;
        }

        last_position_considered = current_offset;
        
        if (alleleFreq.lodVsRef >= 5) { this.output.print(alleleFreq.asGFFString()); }
        if (LOG_METRICS) metrics.printMetricsAtLocusIntervals(1000);
        return "";
    }

    static int nuc2num[];
    static char num2nuc[];
    public AlleleFrequencyWalker() {
        nuc2num = new int[128];
        nuc2num['A'] = 0;
        nuc2num['C'] = 1;
        nuc2num['T'] = 2;
        nuc2num['G'] = 3;
        nuc2num['a'] = 0;
        nuc2num['c'] = 1;
        nuc2num['t'] = 2;
        nuc2num['g'] = 3;

        num2nuc = new char[4];
        num2nuc[0] = 'A';
        num2nuc[1] = 'C';
        num2nuc[2] = 'T';
        num2nuc[3] = 'G';

        try
        {
            this.random = new java.util.Random(0);
            if ( GFF_OUTPUT_FILE.equals("-") )
                this.output = out;
            else
                this.output = new PrintStream(GFF_OUTPUT_FILE);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(-1);
        }

        //if (System.getenv("N") != null) { this.N = (new Integer(System.getenv("N"))).intValue(); }
        //else { this.N = 2; }
        //
        //if (System.getenv("DOWNSAMPLE") != null) { this.DOWNSAMPLE = (new Integer(System.getenv("DOWNSAMPLE"))).intValue(); }
        //else { this.DOWNSAMPLE = 0; }
    }

    public void onTraversalDone(String result) 
    {
        if (inside_confident_ref_interval)
        {
            // if we have a confident reference interval still hanging open, close it.

            double lod = confident_ref_interval_LOD_sum / confident_ref_interval_length;

            output.format("%s\tCALLER\tREFERENCE\t%d\t%d\t%f\t.\t.\tLENGTH %d\n",
                            confident_ref_interval_contig,
                            confident_ref_interval_start,
                            last_position_considered,
                            lod,
                            (int)(confident_ref_interval_length));

            inside_confident_ref_interval = false;
        }

        if (LOG_METRICS) metrics.printMetrics();

        try
        {
	        this.output.flush();
            if ( ! GFF_OUTPUT_FILE.equals("-") )
                this.output.close();
            this.output.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(-1);
        }

    }

    static void print_base_qual_matrix(double [][]quals) {
        // Print quals for debugging
        System.out.println("Base quality matrix");
        int numReads = quals.length;
        for (int b=0; b<4; b++) {
            System.out.print(num2nuc[b]);
            for (int i=0; i < numReads; i++){
                System.out.format(" %.4f", quals[i][b]);
            }
            System.out.println();
        }
    }

    public static void main(String[] args)
    {

        AlleleFrequencyWalker.binomialProb(5,10,0.5);
        AlleleFrequencyWalker.binomialProb(50,100,0.5);
        AlleleFrequencyWalker.binomialProb(1500,2965,0.508065);

        {
            int N = 2;
	        byte[] het_bases = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0,  
	                        1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
	        double[][] het_quals = {{0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0}};
	        AlleleFrequencyWalker w = new AlleleFrequencyWalker();
	        AlleleFrequencyEstimate estimate = w.AlleleFrequencyEstimator(null, N, het_bases, het_quals, 0, 1, 20);
	        System.out.print(String.format("50%% Het : %s %c %c %f %f %f %d %s\n", 
	                                        "null", estimate.ref, estimate.alt, estimate.qhat, estimate.qstar, estimate.lodVsRef, 20, "null"));
        }

        {
	        byte[] het_bases = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
	                        
	        double[][] het_quals = {
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
	                                {0.001/3.0, 0.999, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                    {0.999, 0.001/3.0, 0.001/3.0, 0.001/3.0},
                                   };

            assert(het_bases.length == het_quals.length);

            int N = 10;
	        AlleleFrequencyWalker w = new AlleleFrequencyWalker();
            w.N = 10;
	        AlleleFrequencyEstimate estimate = w.AlleleFrequencyEstimator(null, N, het_bases, het_quals, 0, 1, 20);
	        System.out.print(String.format("10%% Het : %s %c %c %f %f %f %d %s\n", 
	                                        "null", estimate.ref, estimate.alt, estimate.qhat, estimate.qstar, estimate.lodVsRef, 20, "null"));
        }

    }

}
















