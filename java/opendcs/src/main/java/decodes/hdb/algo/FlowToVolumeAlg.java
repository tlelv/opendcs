package decodes.hdb.algo;

import java.util.Date;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
// this new import was added by M. Bogner Aug 2012 for the 3.0 CP upgrade project
import decodes.tsdb.algo.AWAlgoType;
// this new import was added by M. Bogner March 2013 for the 5.3 CP upgrade project
// new class handles surrogate keys as an object
import decodes.sql.DbKey;
import org.opendcs.annotations.PropertySpec;
import org.opendcs.annotations.algorithm.Algorithm;
import org.opendcs.annotations.algorithm.Input;
import org.opendcs.annotations.algorithm.Output;
import java.util.TimeZone;
import java.util.Calendar;
import java.util.GregorianCalendar;

import decodes.hdb.HdbFlags;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;

import ilex.util.DatePair;
import decodes.tsdb.ParmRef;
import decodes.hdb.dbutils.DBAccess;
import decodes.hdb.dbutils.DataObject;
import decodes.tsdb.DbCompException;
import decodes.util.DecodesSettings;
import decodes.hdb.dbutils.RBASEUtils;


@Algorithm(description = "Algorithm calculates volume using average interval flows\n\n" +
	"Properties:\n\n" +
	"PARTIAL_CALCULATIONS: true/false : use if a running volume is wanted\n\n" +
	"MIN_VALUES_REQUIRED: Number: the required number of records that must exist\n" +
	"for calculation to succeed Value 0 means ALL interval observations must exist\n\n" +
	"MIN_VALUES_DESIRED: Number: will set flag field for result if # of inputs are &lt;= this value\n\n" +
	"VALIDATION_FLAG: set the validation flag on output record\n\n" + 
	"OBSERVATIONS_CALCULATION : true/false : use exact number of interval\n" +
	"observations to calculate flow is only programmed to work for hourly and\n" +
	"FLOW_FACTOR: Number: use as multiplier for flow to volume factor (2.0,1.98) :\n" +
	"daily inputs!\n\n" +
	"Default: 86400/43560]\n\n" +
	"NO_ROUNDING: if no rounding in the calculation is desired; default is FALSE")
public class FlowToVolumeAlg extends decodes.tsdb.algo.AW_AlgorithmBase
{
	@Input
	public double input;

	// Enter any local class variables needed by the algorithm.
	// version 1.0.07 modded for CP upgrade 3.0 project by M. Bogner Aug 2012
	// version 1.0.08 enhanced for LC request to choose if rounding desired by
	// M. Bogner February 2013
	// version 1.0.09 enhanced for the CP upgrade 5.3 project where the
	// surrogate keys where handles in a new DBkey class
	String alg_ver = "1.0.09";
	String query;
	boolean do_setoutput = true;
	boolean is_current_period;
	String flags;
	Date date_out;
	Double tally;
	int total_count;
	long mvr_count;
	long mvd_count;

	@Output(type = Double.class)
	public NamedVariable output = new NamedVariable("output", 0);

	@PropertySpec(value = "false")
	public boolean no_rounding = false;
	@PropertySpec(value = "false")
	public boolean partial_calculations = false;
	@PropertySpec(value = "false")
	public boolean observations_calculation = false;
	@PropertySpec(value = "1")
	public long min_values_required = 1;
	@PropertySpec(value = "0")
	public long min_values_desired = 0;
	@PropertySpec(value = "")
	public String validation_flag = "";
	@PropertySpec(value = " 86400/43560 ")
	public String flow_factor = " 86400/43560 ";

	// Allow javac to generate a no-args constructor.

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	protected void initAWAlgorithm() throws DbCompException
	{
		_awAlgoType = AWAlgoType.AGGREGATING;
		_aggPeriodVarRoleName = "output";
	}

	/**
	 * This method is called once before iterating all time slices.
	 */
	protected void beforeTimeSlices() throws DbCompException
	{
		// This code will be executed once before each group of time slices.
		// For TimeSlice algorithms this is done once before all slices.
		// For Aggregating algorithms, this is done before each aggregate
		// period.
		query = null;
		total_count = 0;
		do_setoutput = true;
		flags = "";
		date_out = null;
		tally = 0.0;
	}

	/**
	 * Do the algorithm for a single time slice. AW will fill in user-supplied
	 * code here. Base class will set inputs prior to calling this method. User
	 * code should call one of the setOutput methods for a time-slice output
	 * variable.
	 *
	 * @throw DbCompException (or subclass thereof) if execution of this
	 *        algorithm is to be aborted.
	 */
	protected void doAWTimeSlice() throws DbCompException
	{
		// Enter code to be executed at each time-slice.
		if (!isMissing(input))
		{
			tally += input;
			total_count++;
		}
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	@Override
	protected void afterTimeSlices() throws DbCompException
	{
		// This code will be executed once after each group of time slices.
		// For TimeSlice algorithms this is done once after all slices.
		// For Aggregating algorithms, this is done after each aggregate
		// period.
		//
		// delete any existing value if this period has no records
		// and do nothing else but return
		if (total_count == 0)
		{
			deleteOutput(output);
			return;
		}

		do_setoutput = true;
		ParmRef parmRef = getParmRef("input");
		if (parmRef == null)
		{
			warning("FlowToVolumeAlg: Unknown aggregate control output variable 'INPUT'");
			return;
		}
		String input_interval = parmRef.compParm.getInterval();
		String table_selector = parmRef.compParm.getTableSelector();
		parmRef = getParmRef("output");
		if (parmRef == null)
		{
			warning("FlowToVolumeAlg: Unknown aggregate control output variable 'OUTPUT'");
			return;
		}
		String output_interval = parmRef.compParm.getInterval();
		//

		TimeZone tz = TimeZone.getTimeZone("GMT");
		GregorianCalendar cal = new GregorianCalendar(tz);
		GregorianCalendar cal1 = new GregorianCalendar();
		cal1.setTime(_aggregatePeriodBegin);
		cal.set(cal1.get(Calendar.YEAR), cal1.get(Calendar.MONTH), cal1.get(Calendar.DAY_OF_MONTH), 0, 0);
		mvr_count = min_values_required;
		mvd_count = min_values_desired;

		// first see if there are bad negative min settings for other than a
		// monthly aggregate...
		if (!output_interval.equalsIgnoreCase("month"))
		{
			if (mvr_count < 0 || mvd_count < 0)
			{

				warning("FlowToVolumeAlg-"
					+ alg_ver
					+ " Warning: Illegal negative setting of minimum values criteria for non-Month aggregates");
				warning("FlowToVolumeAlg-" + alg_ver
					+ " Warning: Minimum values criteria for non-Month aggregates set to 1");
				if (mvd_count < 0)
					mvd_count = 1;
				if (mvr_count < 0)
					mvr_count = 1;
			}
			if ((input_interval.equalsIgnoreCase("instant") || output_interval.equalsIgnoreCase("hour"))
				&& mvr_count == 0)
			{

				warning("FlowToVolumeAlg-" + alg_ver
					+ " Warning: Illegal zero setting of minimum values criteria for instant/hour aggregates");
				warning("FlowToVolumeAlg-" + alg_ver
					+ " Warning: Minimum values criteria for instant/hour aggregates set to 1");
				mvr_count = 1;
			}
		}

		// check and set minimums for yearly aggregates
		if (output_interval.equalsIgnoreCase("year") || output_interval.equalsIgnoreCase("wy"))
		{
			if (mvr_count == 0)
			{
				if (input_interval.equalsIgnoreCase("month"))
					mvr_count = 12;
				if (input_interval.equalsIgnoreCase("day"))
					mvr_count = cal.getActualMaximum(Calendar.DAY_OF_YEAR);
				if (input_interval.equalsIgnoreCase("hour"))
					mvr_count = cal.getActualMaximum(Calendar.DAY_OF_YEAR) * 24;
			}
		}

		// check and set minimums for monthly aggregates
		if (output_interval.equalsIgnoreCase("month"))
		{
			int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
			// now if the required numbers are negative then calculate based on
			// total days in month
			if (mvr_count <= 0 && input_interval.equalsIgnoreCase("day"))
				mvr_count = days + mvr_count;
			if (mvr_count <= 0 && input_interval.equalsIgnoreCase("hour"))
				mvr_count = days * 24 + mvr_count;
			if (mvd_count <= 0 && input_interval.equalsIgnoreCase("day"))
				mvd_count = days + mvd_count;
			if (mvd_count <= 0 && input_interval.equalsIgnoreCase("hour"))
				mvd_count = days * 24 + mvd_count;
		}
		//
		// check and set minimums for daily aggregates
		if (output_interval.equalsIgnoreCase("day"))
		{
			if (mvr_count == 0 && input_interval.equalsIgnoreCase("hour"))
			{
				mvr_count = 24;
			}
			else if (mvr_count == 0 && !input_interval.equalsIgnoreCase("day"))
			{
				warning("FlowToVolumeAlg-" + alg_ver
					+ " Warning: Illegal zero setting of minimum values criteria for " + input_interval
					+ " to daily aggregates");
				warning("FlowToVolumeAlg-" + alg_ver
					+ " Warning: Minimum values criteria for daily aggregates set to 1");
				if (mvd_count == 0)
					mvd_count = 1;
				if (mvr_count == 0)
					mvr_count = 1;
			}

		}
		//
		// get the connection and a few other classes so we can do some sql
		try (Connection conn = tsdb.getConnection())
		{
			DBAccess db = new DBAccess(conn);
			DataObject dbobj = new DataObject();
			dbobj.put("ALG_VERSION", alg_ver);
			// this SDI initialization was modified by M. Bogner Mar 2013 for the
			// dbkey surrogate key i
			// class for the 5.3 CP upgrade project
			Integer sdi = (int) getSDI("input").getValue();
			String dt_fmt = "dd-MMM-yyyy HH:mm";

			RBASEUtils rbu = new RBASEUtils(dbobj, conn);
			SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm");
			sdf.setTimeZone(TimeZone.getTimeZone(DecodesSettings.instance().aggregateTimeZone));

			String status = null;

			rbu.getStandardDates(sdi, output_interval, _aggregatePeriodBegin, _aggregatePeriodEnd, dt_fmt);

			double average_flow = tally / (double) total_count;
			double daily_count = (double) total_count;
			double hourly_count = (double) daily_count / 24.00;
			debug3(" Total Count: " + total_count + "   daily_count: " + daily_count + "   hourly Count: "
				+ hourly_count);
			String day_multiplier = " ( to_date('" + (String) dbobj.get("SD_EDT") + "','dd-MM-yyyy HH24:MI') - "
				+ "to_date('" + (String) dbobj.get("SD_SDT") + "','dd-MM-yyyy HH24:MI') )";

			if (observations_calculation && input_interval.equalsIgnoreCase("day"))
				day_multiplier = " " + daily_count;
			if (observations_calculation && input_interval.equalsIgnoreCase("hour"))
				day_multiplier = " " + hourly_count;

			// see if we are in a current window and do the query to do the volume
			// calculation
			String query = "select round( hdb_utilities.get_sdi_unit_factor( " + getSDI("input")
				+ ") * hdb_utilities.get_sdi_unit_factor( " + getSDI("output") + ") * " + flow_factor + " * "
				+ day_multiplier + " * " + average_flow + ",5) volume , " + "hdb_utilities.date_in_window('"
				+ output_interval.toLowerCase() + "',to_date('" + sdf.format(_aggregatePeriodBegin)
				+ "','dd-MM-yyyy HH24:MI')) is_current_period from dual";
			// if rounding not requested modify the query to not include rounding
			// function
			// no rounding capability added 2/2013 per request by LC
			if (no_rounding)
			{
				query = "select hdb_utilities.get_sdi_unit_factor( " + getSDI("input")
					+ ") * hdb_utilities.get_sdi_unit_factor( " + getSDI("output") + ") * " + flow_factor + " * "
					+ day_multiplier + " * " + average_flow + " volume , " + "hdb_utilities.date_in_window('"
					+ output_interval.toLowerCase() + "',to_date('" + sdf.format(_aggregatePeriodBegin)
					+ "','dd-MM-yyyy HH24:MI')) is_current_period from dual";
			}
			// now do the query for all the needed data
			status = db.performQuery(query, dbobj);
			debug3(" SQL STRING:" + query + "   DBOBJ: " + dbobj.toString() + "STATUS:  " + status);

			// see if there was an error
			if (status.startsWith("ERROR"))
			{

				warning(" FlowToVolumeAlg-" + alg_ver + ":  Failed due to following oracle error");
				warning(" FlowToVolumeAlg-" + alg_ver + ": " + status);
				return;
			}
			//
			debug3("FlowToVolumeAlg-" + alg_ver + "  " + _aggregatePeriodEnd + " SDI: " + getSDI("input")
				+ "  MVR: " + mvr_count + " RecordCount: " + total_count);
			// now see how many records were found for this aggregate
			// and see if this calc is in current period and if partial calc is set
			is_current_period = ((String) dbobj.get("is_current_period")).equalsIgnoreCase("Y");
			if (!is_current_period && total_count < mvr_count)
			{
				do_setoutput = false;
				debug1("FlowToVolumeAlg-" + alg_ver + ":  Minimum required records not met for historic period: "
					+ _aggregatePeriodEnd + " SDI: " + getSDI("input") + "  MVR: " + mvr_count + " RecordCount: "
					+ total_count);
			}
			if (is_current_period && !partial_calculations && total_count < mvr_count)
			{
				do_setoutput = false;
				debug1("FlowToVolumeAlg-" + alg_ver + ":  Minimum required records not met for current period: "
					+ _aggregatePeriodEnd + " SDI: " + getSDI("input") + "  MVR: " + mvr_count + " RecordCount: "
					+ total_count);
			}
			//
			//
			// do the volume calculation, set the output if all is successful and
			// set the flags appropriately
			if (do_setoutput)
			{
				// set the dataflags appropriately
				if (total_count < mvd_count)
					flags = flags + "n";
				if (is_current_period && total_count < mvr_count)
				// now we have a partial calculation, so do what needs to be done
				// for partials
				{
					setHdbValidationFlag(output, 'T');
					// call the RBASEUtils merge method to add a "seed record" to
					// cp_historic_computations table
					// this call modified by M. Bogner Mar 2013 for the dbkey
					// surrogate key class for the 5.3 CP upgrade project

					rbu.merge_cp_hist_calc((int) comp.getAppId().getValue(), (int) getSDI("input").getValue(),
						input_interval, _aggregatePeriodBegin, _aggregatePeriodEnd, "dd-MM-yyyy HH:mm",
						tsdb.getWriteModelRunId(), table_selector);

				}

				debug3("FlowToVolumeAlg: Derivation FLAGS: " + flags);
				if (flags != null)
					setHdbDerivationFlag(output, flags);
				Double volume = Double.valueOf(dbobj.get("volume").toString());
				//
				/* added to allow users to automatically set the Validation column */
				if (validation_flag.length() > 0)
					setHdbValidationFlag(output, validation_flag.charAt(1));
				setOutput(output, volume);
			}
		}
		catch (SQLException ex)
		{
			throw new DbCompException("Unable to get connection.", ex);
		}
		//
		// delete any existing value if this calculation failed
		if (!do_setoutput)
		{
			deleteOutput(output);
		}
	}
}
