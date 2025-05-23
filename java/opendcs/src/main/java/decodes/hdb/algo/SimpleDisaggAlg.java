package decodes.hdb.algo;

import java.util.Date;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;
import decodes.tsdb.algo.AW_AlgorithmBase;


import java.util.TimeZone;
import java.util.Calendar;
import java.util.GregorianCalendar;

import decodes.tsdb.IntervalCodes;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;
import ilex.var.NoConversionException;
import org.opendcs.annotations.PropertySpec;
import org.opendcs.annotations.algorithm.Algorithm;
import org.opendcs.annotations.algorithm.Input;
import org.opendcs.annotations.algorithm.Output;

@Algorithm(description = "Dis-aggregates by spreading out the input values to the outputs in various\n" +
"ways (fill, interpolate, split).\n" +
"The interval of the input should always be equal to, or longer than, the output.\n" +
"Example: Input is daily, output is hour. 24 output values are written covering\n" +
"the period of each input.\n" +
"The 'method' property determines how each output period is determined:\n" +
"<ul>\n" +
"  <li>fill (default) - Each output is the same as the input covering the period.\n" +
"      </li>\n" +
"  <li>interp - Determine the output by interpolating between input values</li>\n" +
"  <li>split - Divide the input equally between the outputs for the period.</li>\n" +
"</ul>\n" +
"This disagg algorithm is a simple disaggregation Process, it only works from one\n" + 
"interval to the next one down the interval chain.  Hourly to instantaneous will not work") 
public class SimpleDisaggAlg
	extends AW_AlgorithmBase
{
	@Input
	public double input;

	ParmRef iref = null;
	String iintv = null;
	int isec = 0;
	ParmRef oref = null;
	String ointv = null;
	int osec = 0;
	double prevInputV = 0.0;
	long prevInputT = 0;

	@Output(type = Double.class)
	public NamedVariable output = new NamedVariable("output", 0);

	@PropertySpec(value = "fill") 
	public String method = "fill";

	// Allow javac to generate a no-args constructor.

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	@Override
	protected void initAWAlgorithm( )
		throws DbCompException
	{
		_awAlgoType = AWAlgoType.TIME_SLICE;
	}
	
	/**
	 * This method is called once before iterating all time slices.
	 */
	@Override
	protected void beforeTimeSlices()
		throws DbCompException
	{

		// Get interval of input
		iref = getParmRef("input");
		if (iref == null)
			throw new DbCompException("Cannot determine period of 'input'");

		iintv = iref.compParm.getInterval();
		isec = IntervalCodes.getIntervalSeconds(iintv);
		if (isec == 0)
			throw new DbCompException(
			   "Cannot DisAggregate from 'input' with instantaneous interval.");

		// Get interval of output
		oref = getParmRef("output");
		if (oref == null)
			throw new DbCompException("Cannot determine period of 'output'");
		ointv = oref.compParm.getInterval();
		osec = IntervalCodes.getIntervalSeconds(ointv);
		if (osec == 0)
			throw new DbCompException(
			  "Cannot DisAggregate from 'output' with instantaneous interval.");

info("DisAgg, input interval=" + iintv + ", isec=" + isec
+ ", ointv=" + ointv + ", osec=" + osec + ", method=" + method);
		if (method.equalsIgnoreCase("interp"))
		{
			// For interplation of 1st input, we will need the prev value from
			// the database.
			Date firstTime = baseTimes.first();
			TimedVariable tv = null;
			try { tv = tsdb.getPreviousValue(iref.timeSeries, firstTime); }
			catch(Exception ex)
			{
				throw new DbCompException("DisAgg: " + ex);
			}
			prevInputV = 0.0;
			prevInputT = 0;
			if (tv != null)
			{
				try { prevInputV = tv.getDoubleValue(); }
				catch(NoConversionException ex) {}
				long prevInputT = tv.getTime().getTime();
			}
		}
	}

	/**
	 * Do the algorithm for a single time slice.
	 * AW will fill in user-supplied code here.
	 * Base class will set inputs prior to calling this method.
	 * User code should call one of the setOutput methods for a time-slice
	 * output variable.
	 *
	 * @throw DbCompException (or subclass thereof) if execution of this
	 *        algorithm is to be aborted.
	 */
	@Override
	protected void doAWTimeSlice()
		throws DbCompException
	{

		// if interval of output > interval of input then output a single 
		// value at the input time.
		if (osec >= isec)
		{
			// Just output one value at input time.
			setOutput(output, input);
			return;
		}
		

		long bmsec = _timeSliceBaseTime.getTime();
                debug2("!!!!!  Basetime=  " + _timeSliceBaseTime );

		Date startT = new Date(bmsec);
		Date endT = new Date(bmsec + isec*1000L);

                TimeZone tz = TimeZone.getTimeZone("GMT");
		GregorianCalendar calendar = new GregorianCalendar(tz);
		GregorianCalendar cal1 = new GregorianCalendar();
		cal1.setTime(_timeSliceBaseTime);
		calendar.set(cal1.get(Calendar.YEAR),cal1.get(Calendar.MONTH),cal1.get(Calendar.DAY_OF_MONTH),0,0);

 		int interval = 0;
                int timestep = 1;
		int num_of_intervals = 0; 
		if (ointv.equalsIgnoreCase("year")) interval = Calendar.YEAR;
		if (ointv.equalsIgnoreCase("wy")) interval = Calendar.YEAR;
		if (ointv.equalsIgnoreCase("month")) 
		{  interval = Calendar.MONTH;
		   num_of_intervals = 12;
		}
		if (ointv.equalsIgnoreCase("day")) 
		{
  		   interval = Calendar.HOUR;
		   timestep = 24;
		   num_of_intervals = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
		}

		if (ointv.equalsIgnoreCase("hour"))
		{
  		   interval = Calendar.HOUR;
		   timestep = 24;
		   num_of_intervals = 24;
		}

		double value = -99999D;
		if (method.equalsIgnoreCase("fill")) value = input;
		if (method.equalsIgnoreCase("split")) value = num_of_intervals == 0 ? input : input/(double)num_of_intervals;

		Date t =  null;
        	debug2("interval=" + interval + "   timestep  =  " + timestep);
 		for ( int k = 0; k < num_of_intervals; k++)
		{
		   cal1.set(calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH),calendar.get(Calendar.HOUR),0);
          	   t = cal1.getTime();
		   info(method + " input=" + input + ", n=" + num_of_intervals + ", v=" + value + ", t=" + t);
debug2("GMT CAL VALUES: " + calendar.get(Calendar.YEAR)+"."+calendar.get(Calendar.MONTH)+"."+calendar.get(Calendar.DAY_OF_MONTH)+"."+
calendar.get(Calendar.HOUR)+"."+calendar.get(Calendar.MINUTE)+"."+calendar.get(Calendar.DST_OFFSET));
		   if (!method.equalsIgnoreCase("interp")) setOutput(output, value, t);
                        
		   if (method.equalsIgnoreCase("interp"))
		   {
		     // for 1st time slice, we should have prev V & T from the
			// beforeTimeSlices method.
			// If not, we can't do the interpolation for 1st input value.
			TimedVariable tv =
				iref.timeSeries.findInterp(t.getTime() / 1000L);
			try
			{
				if (tv != null)
				{
				     info("interping: " + tv);
				     setOutput(output, tv.getDoubleValue(), t);
				}
			}
		  	  catch(NoConversionException ex) {}
		    }
		    calendar.add(interval,timestep);
		  }
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	@Override
	protected void afterTimeSlices()
	{
	}
}
