/*
 * Copyright (c) 1998-2018 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.dt.point;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ucar.ma2.Array;
import ucar.ma2.ArrayChar;
import ucar.ma2.ArrayDouble;
import ucar.ma2.DataType;
import ucar.ma2.StructureData;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileSubclass;
import ucar.nc2.Variable;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants.CF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dataset.conv.CF1Convention;
import ucar.nc2.dt.DataIterator;
import ucar.nc2.dt.PointObsDataset;
import ucar.nc2.dt.PointObsDatatype;
import ucar.nc2.dt.TypedDatasetFactory;
import ucar.nc2.ft.FeatureDatasetPoint;
import ucar.nc2.ft.DsgFeatureCollection;
import ucar.nc2.ft.PointFeature;
import ucar.nc2.ft.PointFeatureCollection;
import ucar.nc2.iosp.netcdf3.N3outputStreamWriter;
import ucar.unidata.geoloc.EarthLocation;

/**
 * Write point data in CF point convention.
 * Also experiment with streaming netcdf.
 *
 * @deprecated use WriterCFPointCollection
 * @see "http://cf-pcmdi.llnl.gov/trac/wiki/PointObservationConventions"
 * @author caron
 */
public class WriterCFPointDataset {
  private static final String recordDimName = "record";
  private static final String latName = "latitude";
  private static final String lonName = "longitude";
  private static final String altName = "altitude";
  private static final String timeName = "time";

  private NetcdfFileStream ncfileOut;
  private List<Attribute> globalAtts;
  private String altUnits;

  private Set<Dimension> dimSet = new HashSet<Dimension>();
  private List<Variable> recordVars = new ArrayList<Variable>();

  private boolean useAlt = false;

  private boolean debug = false;

  public WriterCFPointDataset(DataOutputStream stream, List<Attribute> globalAtts, String altUnits) {
    ncfileOut = new NetcdfFileStream(stream);
    this.globalAtts = globalAtts;
    this.altUnits = altUnits;
    useAlt = (altUnits != null);
  }

  public void writeHeader(List<? extends VariableSimpleIF> vars, int numrec) throws IOException {
    createGlobalAttributes();
    createRecordVariables(vars);

    ncfileOut.finish(); // done with define mode
    ncfileOut.writeHeader(numrec);
  }

  private void createGlobalAttributes() {
    if (globalAtts != null) {
      for (Attribute att : globalAtts) {
        if (att.getShortName().equalsIgnoreCase("cdm_data_type"))
          continue;
        if (att.getShortName().equalsIgnoreCase("cdm_datatype"))
          continue;
        if (att.getShortName().equalsIgnoreCase("thredds_data_type"))
          continue;

        ncfileOut.addAttribute(null, att);
      }
    }
    ncfileOut.addAttribute(null, new Attribute(CDM.CONVENTIONS, "CF-1")); // LOOK CF-1.?
    ncfileOut.addAttribute(null, new Attribute(CF.FEATURE_TYPE, CF.FeatureType.point.name()));
  }

  // private ArrayInt.D1 timeArray = new ArrayInt.D1(1);
  // private ArrayInt.D1 parentArray = new ArrayInt.D1(1);

  private void createRecordVariables(List<? extends VariableSimpleIF> dataVars) {

    ncfileOut.addDimension(null, new Dimension(recordDimName, 0, true, true, false));

    // time variable
    Variable timeVar = ncfileOut.addVariable(null, timeName, DataType.DOUBLE, recordDimName);
    timeVar.addAttribute(new Attribute(CDM.UNITS, "secs since 1970-01-01 00:00:00"));
    timeVar.addAttribute(new Attribute(CDM.LONG_NAME, "date/time of observation"));
    recordVars.add(timeVar);

    // latitude variable
    Variable latVar = ncfileOut.addVariable(null, latName, DataType.DOUBLE, recordDimName);
    latVar.addAttribute(new Attribute(CDM.UNITS, "degrees_north"));
    latVar.addAttribute(new Attribute(CDM.LONG_NAME, "latitude of observation"));
    latVar.addAttribute(new Attribute("standard_name", "latitude"));
    recordVars.add(latVar);

    // longitude variable
    Variable lonVar = ncfileOut.addVariable(null, lonName, DataType.DOUBLE, recordDimName);
    lonVar.addAttribute(new Attribute(CDM.UNITS, "degrees_east"));
    lonVar.addAttribute(new Attribute(CDM.LONG_NAME, "longitude of observation"));
    lonVar.addAttribute(new Attribute("standard_name", "longitude"));
    recordVars.add(lonVar);

    if (useAlt) {
      // altitude variable
      Variable altVar = ncfileOut.addVariable(null, altName, DataType.DOUBLE, recordDimName);
      altVar.addAttribute(new Attribute(CDM.UNITS, altUnits));
      altVar.addAttribute(new Attribute(CDM.LONG_NAME, "altitude of observation"));
      altVar.addAttribute(new Attribute("standard_name", "longitude"));
      altVar.addAttribute(new Attribute(CF.POSITIVE, CF1Convention.getZisPositive(altName, altUnits)));
      recordVars.add(altVar);
    }

    String coordinates = timeName + " " + latName + " " + lonName;
    if (useAlt)
      coordinates = coordinates + " " + altName;
    Attribute coordAtt = new Attribute(CF.COORDINATES, coordinates);

    // find all dimensions needed by the data variables
    for (VariableSimpleIF var : dataVars) {
      List<Dimension> dims = var.getDimensions();
      dimSet.addAll(dims);
    }

    // add them
    for (Dimension d : dimSet) {
      if (isExtraDimension(d))
        ncfileOut.addDimension(null, new Dimension(d.getShortName(), d.getLength(), true, false, d.isVariableLength()));
    }

    // add the data variables all using the record dimension
    for (VariableSimpleIF oldVar : dataVars) {
      if (ncfileOut.findVariable(oldVar.getShortName()) != null)
        continue;
      List<Dimension> dims = oldVar.getDimensions();
      StringBuilder dimNames = new StringBuilder(recordDimName);
      for (Dimension d : dims) {
        if (isExtraDimension(d))
          dimNames.append(" ").append(d.getShortName());
      }
      Variable newVar = ncfileOut.addVariable(null, oldVar.getShortName(), oldVar.getDataType(), dimNames.toString());
      recordVars.add(newVar);

      newVar.addAll(oldVar.attributes());
      newVar.addAttribute(coordAtt);
    }

  }

  // LOOK kludge to identify time dimension
  private boolean isExtraDimension(Dimension d) {
    return (!d.isUnlimited() && !d.getShortName().equalsIgnoreCase("time"));
  }

  public void writeRecord(double lat, double lon, double alt, Date time, double[] vals, String[] svals)
      throws IOException {
    int count = writeCoordinates(lat, lon, alt, time);

    Variable v;

    // double data
    for (double val : vals) {
      ArrayDouble.D0 data = new ArrayDouble.D0();
      data.set(val);
      v = recordVars.get(count++);
      v.setCachedData(data, false);
    }

    // String data
    for (String sval : svals) {
      v = recordVars.get(count++);
      int strlen = v.getShape(1);
      ArrayChar data = ArrayChar.makeFromString(sval, strlen);
      v.setCachedData(data, false);
    }

    ncfileOut.writeRecordData(recordVars);
  }

  private int writeCoordinates(double lat, double lon, double alt, Date time) {
    int count = 0;

    // time
    ArrayDouble.D0 tdata = new ArrayDouble.D0();
    double secs = (double) (time.getTime() / 1000.);
    tdata.set(secs);
    Variable v = recordVars.get(count++);
    v.setCachedData(tdata, false);

    // lat
    ArrayDouble.D0 latData = new ArrayDouble.D0();
    latData.set(lat);
    v = recordVars.get(count++);
    v.setCachedData(latData, false);

    // lon
    ArrayDouble.D0 lonData = new ArrayDouble.D0();
    lonData.set(lon);
    v = recordVars.get(count++);
    v.setCachedData(lonData, false);

    // alt
    if (useAlt) {
      ArrayDouble.D0 altData = new ArrayDouble.D0();
      altData.set(alt);
      v = recordVars.get(count++);
      v.setCachedData(altData, false);
    }

    return count;
  }

  public void writeRecord(PointFeature pf, StructureData sdata) throws IOException {
    if (debug)
      System.out.println("PointFeature= " + pf);

    EarthLocation loc = pf.getLocation();
    int count = writeCoordinates(loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
        pf.getObservationTimeAsCalendarDate().toDate());

    for (int i = count; i < recordVars.size(); i++) {
      Variable v = recordVars.get(i);
      v.setCachedData(sdata.getArray(v.getShortName()), false);
    }

    ncfileOut.writeRecordData(recordVars);
  }

  public void writeRecord(PointObsDatatype pobs, StructureData sdata) throws IOException {
    if (debug)
      System.out.println("pobs= " + pobs);

    ucar.unidata.geoloc.EarthLocation loc = pobs.getLocation();
    int count =
        writeCoordinates(loc.getLatitude(), loc.getLongitude(), loc.getAltitude(), pobs.getObservationTimeAsDate());

    for (int i = count; i < recordVars.size(); i++) {
      Variable v = recordVars.get(i);
      if (debug)
        System.out.println(" var= " + v.getShortName());
      // assert v.hasCachedData(); ??
      v.setCachedData(sdata.getArray(v.getShortName()), false);
    }

    ncfileOut.writeRecordData(recordVars);
  }

  public void finish() throws IOException {
    // writeDataFinish();
    ncfileOut.close();
    ncfileOut.stream.flush();
    ncfileOut.stream.close();
  }

  private class NetcdfFileStream extends NetcdfFileSubclass {
    N3outputStreamWriter swriter;
    DataOutputStream stream;

    NetcdfFileStream(DataOutputStream stream) {
      super();
      this.stream = stream;
      swriter = new N3outputStreamWriter(this);
    }

    void writeHeader(int numrec) throws IOException {
      swriter.writeHeader(stream, numrec);
    }

    void writeNonRecordData(String varName, Array data) throws IOException {
      swriter.writeNonRecordData(findVariable(varName), stream, data);
    }

    void writeRecordData(List<Variable> varList) throws IOException {
      swriter.writeRecordData(stream, varList);
    }
  }


  //////////////////////////////////////////////////////////////////////////////////

  /**
   * Write a ucar.nc2.ft.PointFeatureCollection in CF point format.
   *
   * @param pfDataset find the first PointFeatureCollection, and write all data from it
   * @param fileOut write to this netcdf-3 file
   * @return number of records written
   * @throws IOException on read/write error, or if no PointFeatureCollection in pfDataset
   */
  public static int writePointFeatureCollection(FeatureDatasetPoint pfDataset, String fileOut) throws IOException {
    // extract the PointFeatureCollection
    PointFeatureCollection pointFeatureCollection = null;
    List<DsgFeatureCollection> featureCollectionList = pfDataset.getPointFeatureCollectionList();
    for (DsgFeatureCollection featureCollection : featureCollectionList) {
      if (featureCollection instanceof PointFeatureCollection)
        pointFeatureCollection = (PointFeatureCollection) featureCollection;
    }
    if (null == pointFeatureCollection)
      throw new IOException("There is no PointFeatureCollection in  " + pfDataset.getLocation());

    long start = System.currentTimeMillis();

    FileOutputStream fos = new FileOutputStream(fileOut);
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos, 10000));
    WriterCFPointDataset writer = null;

    // LOOK BAD
    List<VariableSimpleIF> dataVars = new ArrayList<VariableSimpleIF>();
    ucar.nc2.NetcdfFile ncfile = pfDataset.getNetcdfFile();
    if ((ncfile == null) || !(ncfile instanceof NetcdfDataset)) {
      dataVars.addAll(pfDataset.getDataVariables());
    } else {
      NetcdfDataset ncd = (NetcdfDataset) ncfile;
      for (VariableSimpleIF vs : pfDataset.getDataVariables()) {
        if (ncd.findCoordinateAxis(vs.getShortName()) == null)
          dataVars.add(vs);
      }
    }

    int count = 0;
    for (PointFeature pointFeature : pointFeatureCollection) {
      StructureData data = pointFeature.getDataAll();
      if (count == 0) {
        EarthLocation loc = pointFeature.getLocation(); // LOOK we dont know this until we see the obs
        String altUnits = Double.isNaN(loc.getAltitude()) ? null : "meters"; // LOOK units may be wrong
        writer = new WriterCFPointDataset(out, pfDataset.getGlobalAttributes(), altUnits);
        writer.writeHeader(dataVars, -1);
      }
      writer.writeRecord(pointFeature, data);
      count++;
    }

    writer.finish();
    out.flush();
    out.close();

    long took = System.currentTimeMillis() - start;
    System.out.printf("Write %d records from %s to %s took %d msecs %n", count, pfDataset.getLocation(), fileOut, took);
    return count;
  }

  /**
   * Open a ucar.nc2.dt.PointObsDataset, write out in CF point format.
   *
   * @param fileIn open through TypedDatasetFactory.open(FeatureType.POINT, ..)
   * @param fileOut write to tehis netcdf-3 file
   * @param inMemory if true, write in memory for efficiency
   * @throws IOException on read/write error
   */
  public static void rewritePointObsDataset(String fileIn, String fileOut, boolean inMemory) throws IOException {
    System.out.println("Rewrite .nc files from " + fileIn + " to " + fileOut + "inMem= " + inMemory);

    long start = System.currentTimeMillis();

    // do it in memory for speed
    NetcdfFile ncfile = inMemory ? NetcdfFile.openInMemory(fileIn) : NetcdfFile.open(fileIn);
    NetcdfDataset ncd = new NetcdfDataset(ncfile);

    StringBuilder errlog = new StringBuilder();
    PointObsDataset pobsDataset = (PointObsDataset) TypedDatasetFactory.open(FeatureType.POINT, ncd, null, errlog);

    FileOutputStream fos = new FileOutputStream(fileOut);
    DataOutputStream out = new DataOutputStream(fos);
    WriterCFPointDataset writer = null;

    DataIterator iter = pobsDataset.getDataIterator(1000 * 1000);
    while (iter.hasNext()) {
      PointObsDatatype pobsData = (PointObsDatatype) iter.nextData();
      StructureData sdata = pobsData.getData();
      if (writer == null) {
        ucar.unidata.geoloc.EarthLocation loc = pobsData.getLocation();
        String altUnits = Double.isNaN(loc.getAltitude()) ? null : "meters";
        writer = new WriterCFPointDataset(out, ncfile.getGlobalAttributes(), altUnits);
        writer.writeHeader(pobsDataset.getDataVariables(), -1);
      }
      writer.writeRecord(pobsData, sdata);
    }

    writer.finish();

    long took = System.currentTimeMillis() - start;
    System.out.println("Rewrite " + fileIn + " to " + fileOut + " took = " + took);
  }

  public static void main(String args[]) throws IOException {
    String location = "R:/testdata/point/netcdf/02092412_metar.nc";
    File file = new File(location);
    rewritePointObsDataset(location, "C:/TEMP/" + file.getName(), true);
  }
}
