package vreAnalyzer.VariantPath;

import java.awt.Shape;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ToolTipManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.BlockView;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;

import soot.SootClass;
import soot.SootMethod;
import vreAnalyzer.CSV.CSVWriter;
import vreAnalyzer.Elements.CallSite;
import vreAnalyzer.UI.MainFrame;
import vreAnalyzer.UI.NonEditableModel;
import vreAnalyzer.Util.Graphviz.ImageDisplay;
import vreAnalyzer.Variants.BindingResolver;
import vreAnalyzer.Variants.ConditionCheck;
import vreAnalyzer.Variants.Variant;

public class VariantPathToTable {
	// 单例模式
	public static VariantPathToTable instance;
	// 从表格行 对应到 路径id
	private static Map<Integer,Integer> rowToPathId = new HashMap<Integer,Integer>();
	// 从路径id 对应到 图片文件上
	private static Map<Integer,File> pathIdToImgFile = new HashMap<Integer,File>();
	// 对应 从路径id 到 variantfile上
	private static Map<Integer,Set<File>> pathIdToVarFiles = new HashMap<Integer,Set<File>>();
	
	private Map<SootMethod,ConditionCheck> methodToConditionCheck;
	
	private boolean verbose = true;
	private static int tableRowIndex = 0;
	
	/*
	 * 表格结构
	 * 1. Variant Path
	 * 2. Variant List
	 * 3. Classes
	 * 4. Method
	 * 5. Branch contains?
	 * 6. Branch Value
	 */
	
	public static VariantPathToTable inst(){
		if(instance==null)
			instance = new VariantPathToTable();
		return instance;
	}
	
	private JTable variantPathTable;
	private NonEditableModel varitablePathModel;
	
	public VariantPathToTable(){	
		variantPathTable = MainFrame.inst().getVariantPathTable();
		varitablePathModel = (NonEditableModel) variantPathTable.getModel();
		methodToConditionCheck = BindingResolver.inst().getmethodToConditionCheck();
	}
	
	
	public void addARowToTable(int pathId,CallSite site,VariantPath vpath, Set<Variant>variants,Set<File> variantfile,CSVWriter writer){
		// Caller 函数
		//"VariantPath Id,CallSite,Variants List,Classes,Methods"
		SootMethod callerMethod = vpath.getCallerMethod();
		ConditionCheck callercondCheck = methodToConditionCheck.get(callerMethod);
		String idList = "";
		idList += "[";
		
		for(Variant variant:variants){
			idList += variant.getVariantId();
			idList += ",";
		}
		
		rowToPathId.put(VariantPathToTable.tableRowIndex, pathId);
		if(verbose){
			System.out.println("Add Table to row:"+VariantPathToTable.tableRowIndex+"\twith pathId:"+pathId);
		}
		if(variants.size()>=1){
			idList = idList.substring(0, idList.length()-1);
		}
		idList += "]";
		varitablePathModel.addRow(new Object[]{pathId,idList});
		pathIdToVarFiles.put(pathId, variantfile);
		/*
		 * 写入csv文件
		 * VariantPath Id,
		 * Variants List,
		 * Classes,
		 * Methods,
		 * Branch Contains,
		 * Branch Value,
		 * Override
		 * Overload
		 */
		Set<SootClass> classSet = new HashSet<SootClass>();
		Map<SootMethod,Set<SootMethod>> callerToCallees = new HashMap<SootMethod,Set<SootMethod>>(); 
		for(Variant variant:variants){
			classSet.addAll(variant.getAllClasses());
			Set<SootMethod> callees = new HashSet<SootMethod>();
			callees.addAll(variant.getCalleeMethod());
			if(callerToCallees.containsKey(variant.getCallerMethod())){
				callerToCallees.get(variant.getCallerMethod()).addAll(callees);
			}else{
				callerToCallees.put(variant.getCallerMethod(), callees);
			}
		}
		
		String classsetString = convertClassessetToString(classSet);
		
		String callsiteString = "-";
		if(site!=null){
			callsiteString = site.toString();
		}
		//VariantPath Id,CallSite,Variants List,Classes,Methods
		String csvString = pathId+","// 这个Variant Path的id
		+"\""+callsiteString+"\","// callsite 字符串
		+"\""+idList+"\","// 这个路径上的所有variant的id
		+"\""+classsetString+"\","// 这个路径上涉及的类名
		+"\""+coverMethodMaptoString(callerToCallees)+"\"";// 路径上的method <caller,callee>对
		
		writer.println(csvString);
		if(verbose){
			System.out.println("Write to csv:"+csvString);
		}
		VariantPathToTable.tableRowIndex++;
	}
	

	private String convertClassessetToString(Set<SootClass> classSet) {
		String classString = "[";
		for(SootClass cls:classSet){
			classString += cls.getName();
			classString += ",";
		}
		if(classSet.size()>=1){
			classString = classString.substring(0, classString.length()-1);
		}
		classString += "]";
		return classString;
	}
	/**
	 * 加入路径的按键监听
	 */
	public void addPathListener() {
		pathIdToImgFile = VariantPathAnalysis.inst().getpathIdToImgFile();
		variantPathTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if(variantPathTable.getSelectedRow() > -1){
					int selectedRow = variantPathTable.getSelectedRow();
					int pathId = rowToPathId.get(selectedRow);
					File img = pathIdToImgFile.get(pathId);
					JPanel imagePanel = new JPanel();
					try {
						ImageDisplay imdisplay = new ImageDisplay(img,imagePanel);
						MainFrame.inst().getImageDisplayPanel().setViewportView(imdisplay.getImagePanel());
						Set<File>variantFiles = pathIdToVarFiles.get(pathId);
						// 显示文件
						/*
						 * variant_sourceName.html
						 * 1. 是否会有多个响应？
						 * 2. 如果有如何处理
						 */
						if(variantFiles.size()==1){
							File selectedfile = null;
							for(File file:variantFiles){
								selectedfile = file;
							}
							// 在屏幕上srctxtpanel上显示这个内容
							JEditorPane source_annotatedDisplayArea = MainFrame.inst().getSrcTextDisplayArea();
							source_annotatedDisplayArea.setContentType("text/html");
							source_annotatedDisplayArea.setEditorKit(new TooltipEditorKit());
							ToolTipManager.sharedInstance().registerComponent(source_annotatedDisplayArea);
							source_annotatedDisplayArea.addHyperlinkListener(new HyperlinkListener(){
							String tooltip;
								@Override
								public void hyperlinkUpdate(HyperlinkEvent e) {
									JEditorPane editor = (JEditorPane) e.getSource();
							        if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED){
							          
							        }else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED){
								          tooltip = editor.getToolTipText();
								          javax.swing.text.Element elem = e.getSourceElement();
								          if (elem != null) {
									            AttributeSet attr = elem.getAttributes();
									            AttributeSet a = (AttributeSet) attr.getAttribute(HTML.Tag.A);
									            if (a != null) {
									            	editor.setToolTipText((String) a.getAttribute(HTML.Attribute.TITLE));
									            }
								          }
							        }else if (e.getEventType() == HyperlinkEvent.EventType.EXITED){
							        	editor.setToolTipText(tooltip);
							        }
								}
							});
							List<String> content;
							try {
								content = Files.readAllLines(selectedfile.toPath(),Charset.defaultCharset());
								String allString = "";
								for(String subcontent:content){
									allString+=subcontent;
									allString+="\n";
								}
								source_annotatedDisplayArea.setText("");
								source_annotatedDisplayArea.setText(allString);
								source_annotatedDisplayArea.setCaretPosition(0);
							} catch (IOException e1) {
								e1.printStackTrace();
							}
						}else{
							//有多个文件绑定在这个path上
							new PopupMenu(variantFiles,variantPathTable);
						}
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				}
			}
		});
	}
	
	/**
	 * HTML 显示	
	 */
	class TooltipEditorKit extends HTMLEditorKit {
	    @Override public ViewFactory getViewFactory() {
	        return new HTMLFactory() {
	            @Override public View create(Element elem) {
	                AttributeSet attrs = elem.getAttributes();
	                Object elementName = attrs.getAttribute(AbstractDocument.ElementNameAttribute);
	                Object o = elementName==null ? attrs.getAttribute(StyleConstants.NameAttribute) : null;
	                if (o instanceof HTML.Tag) {
	                    HTML.Tag kind = (HTML.Tag) o;
	                    if (kind == HTML.Tag.DIV) {
	                        return new BlockView(elem, View.Y_AXIS) {
	                            @Override public String getToolTipText(float x, float y, Shape allocation) {
	                                String s = super.getToolTipText(x, y, allocation);
	                                if (s==null) {
	                                    s = (String) getElement().getAttributes().getAttribute(HTML.Attribute.TITLE);
	                                }
	                                return s;
	                            }
	                        };
	                    }
	                }
	                return super.create(elem);
	            }
	        };
	    }
	}
	
	/**
	 * @param callerToCallees 从Caller函数到Callee函数的对应
	 * @return 返回<caller,callee> 对的字符串表示
	 */
	public String coverMethodMaptoString(Map<SootMethod,Set<SootMethod>> callerToCallees){
		/**
		 * [caller->callee]@[XXX->XXX]
		 */
		String callerToCalleesString = "";
		for(Map.Entry<SootMethod, Set<SootMethod>>entry:callerToCallees.entrySet()){
			callerToCalleesString += "[";
			SootMethod caller = entry.getKey();
			callerToCalleesString += caller.getName();
			callerToCalleesString += "->";
			Set<SootMethod> callees = entry.getValue();
			for(SootMethod callee:callees){
				callerToCalleesString += callee.getName();
				callerToCalleesString += ",";
			}
			if(callees.size()>=1){
				callerToCalleesString = callerToCalleesString.substring(0, callerToCalleesString.length()-1);
			}
			callerToCalleesString += "]";
		}
		return callerToCalleesString;
	}
	
}
