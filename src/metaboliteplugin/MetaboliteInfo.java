//////////////////////////////////////////////////////////////////
//Creat a sidepanel in Pathvisio for the metabolite information.//
//////////////////////////////////////////////////////////////////

package metaboliteplugin;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLEditorKit;

import net.sf.jniinchi.INCHI_RET;
import net.sf.jniinchi.JniInchiAtom;
import net.sf.jniinchi.JniInchiBond;
import net.sf.jniinchi.JniInchiException;
import net.sf.jniinchi.JniInchiInput;
import net.sf.jniinchi.JniInchiOutput;
import net.sf.jniinchi.JniInchiStructure;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.bridgedb.IDMapper;
import org.bridgedb.IDMapperException;
import org.bridgedb.Xref;
import org.bridgedb.bio.BioDataSource;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.inchi.InChIGenerator;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IMolecularFormula;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.BremserOneSphereHOSECodePredictor;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.HOSECodeGenerator;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;
import org.openscience.cdk.tools.manipulator.MolecularFormulaManipulator;
import org.pathvisio.core.ApplicationEvent;
import org.pathvisio.core.Engine;
import org.pathvisio.core.Engine.ApplicationEventListener;
import org.pathvisio.core.data.GdbManager;
import org.pathvisio.core.debug.Logger;
import org.pathvisio.core.model.ObjectType;
import org.pathvisio.core.model.PathwayElement;
import org.pathvisio.core.model.PathwayElementEvent;
import org.pathvisio.core.model.PathwayElementListener;
import org.pathvisio.core.util.Utils;
import org.pathvisio.core.view.GeneProduct;
import org.pathvisio.core.view.SelectionBox.SelectionEvent;
import org.pathvisio.core.view.SelectionBox.SelectionListener;
import org.pathvisio.core.view.VPathway;
import org.pathvisio.core.view.VPathwayElement;
import org.pathvisio.desktop.PvDesktop;
import org.pathvisio.gui.SwingEngine;


public class MetaboliteInfo extends JEditorPane implements SelectionListener, PathwayElementListener, ApplicationEventListener
{

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////PLUGIN WORKING MECHANISM/////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	
	public static final String TITLE = "Metabolite";
		
	private Engine engine;
	private ExecutorService executor;

	PathwayElement input;
	
	final static int maxThreads = 1;
	volatile ThreadGroup threads;
	volatile Thread lastThread;
	
	private GdbManager gdbManager;
	private final SwingEngine se;

	public MetaboliteInfo(SwingEngine se)
	{
//		super();
		Engine engine = se.getEngine();
		engine.addApplicationEventListener(this);
		VPathway vp = engine.getActiveVPathway();
		if(vp != null) vp.addSelectionListener(this);
		this.se = se;
		this.gdbManager = se.getGdbManager();
		
		addHyperlinkListener(se);
		setEditable(false);
		setContentType("text/html");
				
		executor = Executors.newSingleThreadExecutor();

		//Workaround for #1313
		//Cause is java bug: http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6993691
		setEditorKit(new HTMLEditorKit() {
			protected Parser getParser() {
				try {
					Class c = Class
							.forName("javax.swing.text.html.parser.ParserDelegator");
					Parser defaultParser = (Parser) c.newInstance();
					return defaultParser;
				} catch (Throwable e) {
				}
				return null;
			}
		});
	}
		
	public void setInput(final PathwayElement e) 
	{
		//System.err.println("===== SetInput Called ==== " + e);
		
		if(e == input) return; //Don't set same input twice
		
		//Remove pathwaylistener from old input
		if(input != null) input.removeListener(this);
		
		if(e == null /*|| e.getObjectType() != ObjectType.DATANODE*/) {
			input = null;
			setText("<p>No pathway element is selected.</p>");
		} 
		else {
			input = e;
			input.addListener(this);
			doQuery();
		}
	}

	private void doQuery() 
	{
		setText("Loading");
		currRef = input.getXref();
		
		executor.execute(new Runnable()
		{
			public void run()
			{
				if(input == null) return;
				final String txt = getContent(input);

				SwingUtilities.invokeLater(new Runnable()
				{
					public void run()
					{
						setText(txt);
						setCaretPosition(0); // scroll to top.
					}
				});
			}
		});
	}
			
	public void selectionEvent(SelectionEvent e) 
	{
		switch(e.type) {
		case SelectionEvent.OBJECT_ADDED:
			//Just take the first DataNode in the selection
			Iterator<VPathwayElement> it = e.selection.iterator();
			while(it.hasNext()) {
				VPathwayElement o = it.next();
				if(o instanceof GeneProduct) {
					setInput(((GeneProduct)o).getPathwayElement());
					break;
				}
			}
			break;
		case SelectionEvent.OBJECT_REMOVED:
			if(e.selection.size() != 0) break;
		case SelectionEvent.SELECTION_CLEARED:
			setInput(null);
			break;
		}
	}

	public void applicationEvent(ApplicationEvent e) {
		if(e.getType() == ApplicationEvent.Type.VPATHWAY_CREATED) {
			((VPathway)e.getSource()).addSelectionListener(this);
		}
	}
		
	Xref currRef;
	
	public void gmmlObjectModified(PathwayElementEvent e) {
		PathwayElement pe = e.getModifiedPathwayElement();
		if(input != null) {
			Xref nref = new Xref (pe.getGeneID(), input.getDataSource());
			if(!nref.equals(currRef)) 
			{
				doQuery();
			}				
		}
	}
	
	private boolean disposed = false;
	public void dispose()
	{
		assert (!disposed);
		engine.removeApplicationEventListener(this);
		VPathway vpwy = engine.getActiveVPathway();
		if (vpwy != null) vpwy.removeSelectionListener(this);
		executor.shutdown();
		disposed = true;
	}
	

	public void done() {}
		

//////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////PLUGIN CONTENT////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private StringBuilder builder = new StringBuilder();
	private String HMDB = null;
	private String smiles = null;
	private String name = null;
	
	public String getContent(PathwayElement e){
			builder.setLength(0);
			Xref ref = e.getXref();
			IDMapper gdb = gdbManager.getCurrentGdb();		
			Set<Xref> destrefs = null;
			try {
				destrefs = gdb.mapID(ref, BioDataSource.HMDB);
				HMDB = destrefs.toString();
				HMDB = HMDB.replace("[Ch:","");
				HMDB = HMDB.replace("]", "");
			} catch (IDMapperException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
				System.out.println("IDMapperException1");
			}
			
			if (e.getDataNodeType().equals("Metabolite"))
			{		
				if (se.getGdbManager().getMetaboliteDb() == null){
					String str = "Please select a metabolite database";
					return str;
				}

				try
				{
					smiles = Utils.oneOf (
							gdbManager.getCurrentGdb().getAttributes (Utils.oneOf(destrefs), "SMILES"));
					name = Utils.oneOf (
							gdbManager.getCurrentGdb().getAttributes (Utils.oneOf(destrefs), "Symbol"));
					builder.append("<h3> General info: </h3>");
					builder.append("<table border=\"0\">");
					builder.append("<tr><td>Name: </td><td>" + name + "</td></tr>");
					builder.append("<tr><td>ID: </td><td>" + HMDB + "</td></tr>");
					builder.append("<tr><td>SMILES: </td><td>" + smiles + "</td></tr>");

					//Execute other methods to get their content
					CreateAtomContainer(smiles);
					CDKInfo();
					Inchi();
					MSImages();
					NMR();
					HOSEGenerator(molecule);
					
					//Add databases that were used.
					builder.append("<p> Databases used: <br />" +
							"<sup>1</sup> <a href=\"http://cactus.nci.nih.gov/chemical/structure\"> Cactus Chemical Identifier Resolver </a><br />" +
							"<sup>2</sup> <a href=\"http://www.hmdb.ca\"> HMDB database </a><br />" +
							"<sup>3</sup> <a href=\"http://sourceforge.net/projects/cdk/\"> Chemistry Development Kit </a>");
				}
				catch (IDMapperException ex)
				{
					System.out.println(ex.getMessage());
					System.out.println("IDMapperException");
					ex.printStackTrace();
					String str = "This HMDB ID was not recognized";
					return str;
				}
			return builder.toString();
			}
			
			else {
				String str = "The plugin only works for metabolites";
				return str;
			}
	}
	public void CDKInfo(){
		IMolecularFormula molForm = MolecularFormulaManipulator.getMolecularFormula(molecule);
		String mf = MolecularFormulaManipulator.getString(molForm);
		System.out.println(mf);
		builder.append("<tr><td>Molecular formula: </td><td>" + mf + "</td></tr>");

	}
				
	public void Inchi() {
//		InChIGeneratorFactory factory = null;
//		InChIGenerator generator = null;
//		System.out.println("inchi 1");
		JniInchiInput input = new JniInchiInput();
		System.out.println(input);
		
	}


		
	public void MSImages(){
		//MS images
		String urlLow = "http://www.hmdb.ca/labm/metabolites/" + HMDB + "/ms/spectra/" + HMDB + "L.png";
		String urlMed = "http://www.hmdb.ca/labm/metabolites/" + HMDB + "/ms/spectraM/" + HMDB + "M.png";
		String urlHigh = "http://www.hmdb.ca/labm/metabolites/" + HMDB + "/ms/spectraH/" + HMDB + "H.png";
		
		builder.append("<br /><h3> Mass spectrometry images: </h3><p>");
		builder.append("<a href=\"" + urlLow + "\"> Low energy MS image </a><br />");
		builder.append("<a href=\"" + urlMed + "\"> Medium energy MS image </a><br />");
		builder.append("<a href=\"" + urlHigh + "\"> High energy MS image </a><br /></p>");
	}
	
	public void NMR(){
		//NMR tables
		builder.append("<h3> NMR peak lists and images predicted by HMDB <sup>2</sup>: </h3>");
		
		//1H NMR predicted spectra
		
		//1H NMR spectrum image link
		String H1NMRLink = "http://www.hmdb.ca/labm/metabolites/" + HMDB + 
				"/chemical/pred_hnmr_spectrum/";
		H1NMRLink = "<a href=\"" + H1NMRLink + "\"> Spectrum image </a><br /><br />";
	
		//1H NMR peak list
		String H1NMR = null;
		try {
		//Set up connection and put InChI key into a string
			HttpClient httpclient = new DefaultHttpClient();
			HttpGet getH1NMR = new HttpGet("http://www.hmdb.ca/labm/metabolites/"
			+ HMDB + "/chemical/pred_hnmr_peaklist/" + HMDB + "_peaks.txt");

			HttpResponse response = null;
			response = httpclient.execute(getH1NMR);

			HttpEntity entity = response.getEntity();
			H1NMR = EntityUtils.toString(entity);
			if (H1NMR.startsWith("Group")){
				H1NMR = H1NMR.replace("	", "</td><td>");
				H1NMR = H1NMR.replace("\n", "</tr><tr>");
				H1NMR = H1NMR.replace("<td></td>", "");
				H1NMR = "Peak list: <br /><table border=\"0\"><tr> " + H1NMR + "</tr></table>";
				//TODO remove last column (the most right)
				builder.append("<p><sup>1</sup>H NMR peak list and image predicted by HMDB<sup>2</sup>: <br /></p>");
				builder.append(H1NMRLink + H1NMR);
			}
			else {builder.append("<i>Peak list is not available</i>");}
		} catch (ClientProtocolException ClientException) {
			System.out.println("clientexception");
			ClientException.printStackTrace();
		} catch (IOException IoException) {
			System.out.println("IOException");
			IoException.printStackTrace();
		} catch (Throwable throwable) {
			System.out.println(throwable.getMessage());
			  System.out.println("Throwableblabla");
			  throwable.printStackTrace();
		}
		
		
		//13C NMR predicted spectra
		
		//13C NMR spectrum image
		String C13NMRLink = "http://www.hmdb.ca/labm/metabolites/" + HMDB + 
				"/chemical/pred_cnmr_spectrum/";
		C13NMRLink = "<a href=\"" + C13NMRLink + "\"> Spectrum image </a><br /><br />";

		//13C NMR peak list
		String C13NMR = null;
		try {
		//Set up connection and put InChI key into a string
			HttpClient httpclient = new DefaultHttpClient();
			HttpGet getC13NMR = new HttpGet("http://www.hmdb.ca/labm/metabolites/"
			+ HMDB + "/chemical/pred_cnmr_peaklist/" + HMDB + "_peaks.txt");
			HttpResponse response = null;
			response = httpclient.execute(getC13NMR);

			HttpEntity entity = response.getEntity();
			C13NMR = EntityUtils.toString(entity);
			if (C13NMR.startsWith("Carbon")){
				C13NMR = C13NMR.replace("	", "</td><td>");
				C13NMR = C13NMR.replace("\n", "</tr><tr>");
				C13NMR = "Peak list: <br /><table border=\"0\"><tr> " + C13NMR + "</tr></table>";
				//TODO remove last column (the most right)
				builder.append("<br /> <p><sup>13</sup>C NMR peak list and image predicted by HMDB<sup>2</sup>: <br /></p>");
				builder.append(C13NMRLink + C13NMR);
			}
			else {builder.append("<i> Peak list could not be loaded</i>");}

		} catch (ClientProtocolException ClientException) {
			System.out.println(ClientException.getMessage());
			ClientException.printStackTrace();
		} catch (IOException IoException) {
			System.out.println(IoException.getMessage());
			IoException.printStackTrace();
		} catch (Throwable throwable) {
			  System.out.println(throwable.getMessage());
			  throwable.printStackTrace();
		}
		
	}
	
/////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////GENERATE IATOMCONTAINER FOR METABOLITE/////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////
	IAtomContainer molecule;
	private void CreateAtomContainer(String sm) {
		String text = sm;
		try
		{
			SmilesParser sp = new SmilesParser(DefaultChemObjectBuilder.getInstance());
			molecule = sp.parseSmiles(text);
			CDKHydrogenAdder adder = CDKHydrogenAdder.getInstance(
					DefaultChemObjectBuilder.getInstance());
			adder.addImplicitHydrogens(molecule);
			AtomContainerManipulator.convertImplicitToExplicitHydrogens(molecule);
		}
		catch (Exception e)
		{
			Logger.log.error ("Chempaint error", e);
		};
	}
	
/////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////NMR SHIFT CALCULATIONS/////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////
	protected transient HOSECodeGenerator hcg = new HOSECodeGenerator();
	protected transient BremserOneSphereHOSECodePredictor predictor = new BremserOneSphereHOSECodePredictor();	
	StringBuilder data = new StringBuilder();
	
	@SuppressWarnings("null")
	public void HOSEGenerator(IAtomContainer ac){
		IAtomContainer mc = ac;
		String hoseCode = null;
		double shift = 0;
		
		
		
		builder.append("<h3> NMR shifts predicted by CDK <sup>3</sup>: </h3>" +
				"<table border=\"0\"> " +
				"<tr><td> Carbon No. </td><td> Neighbors</td><td> Chem. Shift</td></tr>");
		StringBuilder All = new StringBuilder();
		for (int f = 0; f < mc.getAtomCount(); f++) {
			IAtom atom = mc.getAtom(f);
			StringBuilder C = new StringBuilder();
			int g = f+1;
			
			if (atom.getSymbol().equals("C")) {
				try {
					hoseCode = hcg.getHOSECode(mc, mc.getAtom(f), 1);
					hoseCode = hcg.makeBremserCompliant(hoseCode);
					System.out.println(hoseCode);
					shift = predictor.predict(hoseCode);

					List<IAtom> neighborList = mc.getConnectedAtomsList(atom);
					
					for (int i = 0; i < neighborList.size(); i++){
						IAtom neighbor= neighborList.get(i);
						C.append(neighbor.getSymbol());
					}
					builder.append("<tr><td>" + g + "</td><td>" + C.toString() + 
							"</td><td>" + shift + "</td></tr>");
										
				} catch (Throwable e) {
					builder.append("<tr><td>" + g + "</td><td>" + C.toString() + 
							"</td><td>NA</td></tr>");
				}
			}
		}
		builder.append("</table>");
	}

}