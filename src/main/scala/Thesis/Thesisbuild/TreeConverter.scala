package Thesis.Thesisbuild

import stapl.core._
import aima.core.logic.propositional.parsing.ast.Sentence
import aima.core.logic.propositional.visitors.ConvertToDNF
import aima.core.logic.propositional.parsing.ast.ComplexSentence
import aima.core.logic.propositional.parsing.ast.Connective
import aima.core.logic.propositional.parsing.ast.AtomicSentence
import aima.core.logic.propositional.parsing.ast.PropositionSymbol
import aima.core.logic.propositional.visitors.ConvertToCNF
import aima.core.logic.propositional.inference.DPLLSatisfiable
import aima.core.logic.propositional.inference.TTEntails
import aima.core.logic.propositional.kb.KnowledgeBase

class TreeConverter(val root: AbstractPolicy, val knownAttributes : Set[Attribute]) {
  
  /****************************************************************************
   ****************************************************************************
   ****************************MAIN  ALGORITHM*********************************
   ****************************************************************************
   ****************************************************************************/
  
	def reduce(policy : Policy) : Policy = {
	  var policyList = List[Policy]()
	  var reduceList:List[Policy] = Nil
	  while(hasPolicyChildren(policy)) {
	   policyList = getLowestPolicies(policy)
	   reduceList = List[Policy]()
	   for(p <- policyList){
	     reduceList ::= reduceLeaves(p)
	   }
	   for(p <- reduceList){
	     var parent= p.parent.getOrElse(null).asInstanceOf[Policy]
	     var conversion = convertCA(p,parent.pca)
	     combinePolicies(conversion,parent)
	   }
	  }
	  var result = policy
	  if(policy.subpolicies.length>2)
	    if(policy.pca == FirstApplicable)
	       result = reduce(getHighestParent(reduceLeaves(policy)))
	    else
	    	result = reduceLeaves(policy)
	 return result
	}
	
	def normalise(policy : Policy) : AbstractPolicy = {
	  var p = null
	  var newsubs = List[AbstractPolicy]()
	  for (p <- policy.subpolicies.reverse){
	    var rule = p.asInstanceOf[Rule]
	    var sentence = convertToSentence(rule.condition)
	    sentence = ConvertToDNF.convert(sentence)
	    var condition = revertToCondition(sentence)
	    val newRule = new Rule(rule.id)(rule.effect,condition,List[ObligationAction]())
	    newRule.parent = Some(policy)
	    newsubs = newRule :: newsubs
	  }
	  policy.subpolicies = newsubs
	  index = 0
	  propMap = Map[String,Expression]()
	  return policy
	}
	
	def expand(policy : Policy) : AbstractPolicy = {
	  var splitPol = policy
	  var knownAtts = knownAttributes
	  while(canBeSplit(splitPol,knownAtts)){
	    var tmp = splitPolicy(splitPol,knownAtts)
	    knownAtts ++= getAttributes(splitPol.subpolicies.head)
	    splitPol = tmp
	  }
	  ruleIndex = 0
	  policyIndex = 0
	  //expandAnd(getLowestPolicies(policy),knownAtts) TODO test
	  return policy
	}
	
	/***************************************************************************
	****************************************************************************
	***********************HELPER  FUNCTIONS REDUCTION**************************
	****************************************************************************
	****************************************************************************/
	
	def getHighestParent(policy:Policy): Policy = {
	  var continueLoop = true
	  var result = policy
	  while(continueLoop){
		result.parent match {
			case Some(x) => result = x
			case None => continueLoop = false
		}
	  }
	  return result
	}
	
	def hasPolicyChildren(policy:Policy) = policy.subpolicies.exists(_.isInstanceOf[Policy]) 
	
	def getLowestPolicies(policy:Policy) : List[Policy] = {
	  var resultList = List[Policy]()
	  var children = policy.subpolicies
	  var includethis = true
	  for(c <- children)
	  {
	    if(c.isInstanceOf[Policy]){
	      includethis = false
	      resultList :::= getLowestPolicies(c.asInstanceOf[Policy])
	    }
	  }
	  if(includethis) 
	    return policy :: resultList 
	  else return resultList
	}
	
	def reduceLeaves(policy : Policy) : Policy = {
	  var ca = policy.pca
	  var newPolicy:Policy = null
	  if(policy.subpolicies.length <= 2)
	    return policy
	  if(ca == DenyOverrides || ca == PermitOverrides){
	    var rule1 = combineEffect(policy,Permit)
	    var rule2 = combineEffect(policy,Deny)
	    var subpolicies: List[AbstractPolicy] = List(rule1,rule2)
	    subpolicies = subpolicies.filter(c => c != null)
	    newPolicy = new Policy(policy.id)(policy.target,ca,subpolicies,policy.obligations)
	    newPolicy.parent = policy.parent
	    newPolicy.parent match {
	    	case Some(x) => x.subpolicies = replace(x.subpolicies,policy,newPolicy)
	    	case None => 
	    }
	  }else {
		newPolicy = createFAChain(policy)
	  }
	  return newPolicy
	}
	
	def combineEffect(policy: Policy, effect:Effect) : Rule = {
	  var subpols = policy.subpolicies
	  var children = subpols.filter(c => (c.asInstanceOf[Rule]).effect == effect)
	  var conditions = List[Expression]()
	  var newRule:Rule = null
	  if(children.length > 0){
	    for(c <- children.reverse){
	    	conditions ::= c.asInstanceOf[Rule].condition
	    }
	    var newExpression = conditions(0)
	    for(i <- 1 to conditions.length-1){
	      newExpression = newExpression | conditions(i)
	    }
	    newRule = new Rule(children(0).id)(effect,newExpression,List[ObligationAction]())
	  }
	  return newRule
	}
	
	def createFAChain(policy: Policy) : Policy = {
	  var parent = policy.parent
	  var newPol = FAChain(policy.subpolicies.map(x => x.asInstanceOf[Rule]), 0 , policy.id,policy.target,true)
	  newPol.parent = policy.parent
	  newPol.parent match {
	    case Some(x) => x.subpolicies = replace(x.subpolicies,policy,newPol)
	    case None => 
	  }
	  return getLowestPolicies(newPol).head
	}
	
	def replace(policies : List[AbstractPolicy], policy:AbstractPolicy, newPolicy : AbstractPolicy) : List[AbstractPolicy] = {
	  var resList = List[AbstractPolicy]()
	  for(x <- policies.reverse){
	    if(x == policy) resList ::= newPolicy else resList ::= x
	  }
	  return resList
	}
	
	def FAChain(rules : List[Rule], id : Int, startstring : String, target: Expression, includeTarget : Boolean) : Policy = {
	    var Ca:CombinationAlgorithm = DenyOverrides
		if(rules(0).effect == Permit) {
		  Ca = PermitOverrides
		}
		var children = List[AbstractPolicy]()
		var leftChild = rules(0)
		var rightChild:AbstractPolicy = rules(1)
		if(rules.length > 2) 
		  rightChild = FAChain(rules.tail,id+1,startstring, target, false) 
		children = List(leftChild,rightChild)
		if(includeTarget)
		  return new Policy(startstring + id.toString)(target,Ca,children,List[Obligation]())
		else
		  return new Policy(startstring + id.toString)(true,Ca,children,List[Obligation]())
	}
	
	def convertCA(policy: Policy, ca: CombinationAlgorithm) : Policy = {
	  if(policy.pca == ca)
	   return policy
	   var newpolicy: Policy = null
	   if(ca == PermitOverrides && policy.pca == DenyOverrides){
	     if(policy.subpolicies.length == 1){
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,policy.subpolicies)
	     }else if(policy.subpolicies(0).asInstanceOf[Rule].effect == Permit){
	       var A:Rule = policy.subpolicies(0).asInstanceOf[Rule]
	       var B:Rule = policy.subpolicies(1).asInstanceOf[Rule]
	       var newrule = new Rule(A.id)(Permit,And(A.condition,Not(B.condition)),List[ObligationAction]())
	       var subpolicies:List[AbstractPolicy] = List(B, newrule)
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,subpolicies)
	     }else{
	       var A:Rule = policy.subpolicies(0).asInstanceOf[Rule]
	       var B:Rule = policy.subpolicies(1).asInstanceOf[Rule]
	       var newrule = new Rule(B.id)(Permit,And(Not(A.condition),B.condition),List[ObligationAction]())
	       var subpolicies:List[AbstractPolicy] = List(A, newrule)
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,subpolicies)
	     }
	   }else if(ca == PermitOverrides && policy.pca == FirstApplicable){
	     if(policy.subpolicies.length == 1 || policy.subpolicies(0).asInstanceOf[Rule].effect == Permit ){
	       newpolicy = new Policy(policy.id)(policy.target, PermitOverrides,policy.subpolicies)
	     }else{
	       newpolicy = convertCA(new Policy(policy.id)(policy.target,DenyOverrides,policy.subpolicies)
	           ,PermitOverrides)
	     }
	     
	   }else if(ca == DenyOverrides && policy.pca == FirstApplicable){
	      if(policy.subpolicies.length == 1 || policy.subpolicies(0).asInstanceOf[Rule].effect == Deny ){
	       newpolicy = new Policy(policy.id)(policy.target, DenyOverrides,policy.subpolicies)
	     }else{
	       newpolicy = convertCA(new Policy(policy.id)(policy.target,PermitOverrides,policy.subpolicies)
	           ,DenyOverrides)
	     }
	   }else if(ca == DenyOverrides && policy.pca == PermitOverrides){
	     if(policy.subpolicies.length == 1){
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,policy.subpolicies)
	     }else if(policy.subpolicies(0).asInstanceOf[Rule].effect == Permit){
	       var A:Rule = policy.subpolicies(0).asInstanceOf[Rule]
	       var B:Rule = policy.subpolicies(1).asInstanceOf[Rule]
	       var newrule = new Rule(B.id)(Deny,(!A.condition) & B.condition,List[ObligationAction]())	
	       var subpolicies:List[AbstractPolicy] = List(A, newrule)
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,subpolicies)
	     }else{
	       var A:Rule = policy.subpolicies(0).asInstanceOf[Rule]
	       var B:Rule = policy.subpolicies(1).asInstanceOf[Rule]
	       var newrule = new Rule(A.id)(Deny,A.condition & (!B.condition),List[ObligationAction]())
	       var subpolicies:List[AbstractPolicy] = List(newrule,B)
	       newpolicy = new Policy(policy.id)(policy.target,PermitOverrides,subpolicies)
	     }
	   }else if(ca == FirstApplicable && policy.pca == PermitOverrides){
	     if(policy.subpolicies(0).asInstanceOf[Rule].effect == Permit){
	       newpolicy = new Policy(policy.id)(policy.target,FirstApplicable,policy.subpolicies)
	     }else{
	       newpolicy = new Policy(policy.id)(policy.target,FirstApplicable,policy.subpolicies.reverse)
	     }
	     
	   }else if(ca == FirstApplicable && policy.pca == DenyOverrides){
	     if(policy.subpolicies(0).asInstanceOf[Rule].effect == Deny){
	       newpolicy = new Policy(policy.id)(policy.target,FirstApplicable,policy.subpolicies)
	     }else{
	       newpolicy = new Policy(policy.id)(policy.target,FirstApplicable,policy.subpolicies.reverse)
	     }
	   }
	  newpolicy.parent = policy.parent
	  newpolicy.parent match {
	    case Some(x) => x.subpolicies = replace(x.subpolicies,policy,newpolicy)
	    case None => 
	  }
	  return newpolicy
	}
	
	def combinePolicies(child : Policy, parent : Policy) : Policy = {
	  val target = child.target
	  var subpolicies = List[AbstractPolicy]()
	  for(c <- child.subpolicies.reverse){
	    var r = c.asInstanceOf[Rule]
	    r =  new Rule(c.id)(r.effect,target & r.condition, List[ObligationAction]())
	    r.parent = Some(parent)
	    subpolicies ::= r
	  }
	  subpolicies = combineChildren(parent,child,subpolicies)
      parent.subpolicies = subpolicies
	  return parent
	}
	
	def combineChildren(parent: Policy, child: Policy, rules : List[AbstractPolicy]) : List[AbstractPolicy] = {
	  var resultList = List[AbstractPolicy]()
	  for(c <- parent.subpolicies.reverse){
	    if(c == child)
	      resultList :::= rules
	    else
	      resultList ::= c
	    c.parent = Some(parent)
	  }
	  return resultList
	}
	
	
	/***************************************************************************
	****************************************************************************
	********************HELPER  FUNCTIONS NORMALISATION*************************
	****************************************************************************
	****************************************************************************/
	
	def convertToSentence(condition: Expression) : Sentence =  {
	  condition match {
	    case And(x,y) => return new ComplexSentence(Connective.get("&"),convertToSentence(x),convertToSentence(y))
	    case Or(x,y) => return new ComplexSentence(Connective.get("|"),convertToSentence(x),convertToSentence(y))
	    case Not(x) => return new ComplexSentence(Connective.get("~"),convertToSentence(x))
	    case AlwaysTrue => return new PropositionSymbol("true")
	    case AlwaysFalse => return new PropositionSymbol("false")
	    case x if propMap.valuesIterator.contains(x) => return new PropositionSymbol(propMap.filter(p => x==p._2).head._1)
	    case x => {propMap+=(prop+index -> x);index+=1;return new PropositionSymbol(prop+(index-1))}
	  }
	}
	
	def revertToCondition(sentence: Sentence) : Expression = {
	  sentence.getConnective() match {
	    case Connective.AND => return And(revertToCondition(sentence.getSimplerSentence(0)),revertToCondition(sentence.getSimplerSentence(1)))
	    case Connective.OR => return Or(revertToCondition(sentence.getSimplerSentence(0)),revertToCondition(sentence.getSimplerSentence(1)))
	    case Connective.NOT => return Not(revertToCondition(sentence.getSimplerSentence(0)))
	    case null => return propMap.get(sentence.asInstanceOf[PropositionSymbol].getSymbol()).getOrElse(null)
	    case _ => return null
	  }
	}
	
	val prop = "a"
	var index = 0
	var propMap = Map[String,Expression]()
	
	/***************************************************************************
	****************************************************************************
	********************HELPER  FUNCTIONS EXPANSION*****************************
	****************************************************************************
	****************************************************************************/
	
	def canBeSplit(policy : Policy,atts : Set[Attribute]):Boolean = {
	  var common = findCommon(policy.subpolicies(0).asInstanceOf[Rule],policy.subpolicies(1).asInstanceOf[Rule],atts,"Or")
	  return common != null
	  }
	  
	
	def splitPolicy(policy : Policy, atts: Set[Attribute]) : Policy = {
	  var e1 = policy.subpolicies(0).asInstanceOf[Rule].effect
	  var e2 = policy.subpolicies(1).asInstanceOf[Rule].effect
	  var commoncond = findCommon(policy.subpolicies(0).asInstanceOf[Rule],policy.subpolicies(1).asInstanceOf[Rule],atts,"Or")
	  var cond1 = policy.subpolicies(0).asInstanceOf[Rule].condition
	  var cond2 = policy.subpolicies(1).asInstanceOf[Rule].condition
	  var newRule = new Rule("newRule"+ruleIndex)(e1,commoncond,List.empty)
	  newRule.parent = Some(policy)
	  ruleIndex+=1
	  var newSubrule1 = new Rule("newRule"+ruleIndex)(e1,removeCommon(cond1,commoncond),List.empty)
	  var newSubrule2 = new Rule("newRule"+(ruleIndex+1))(e2,removeCommon(cond2,commoncond),List.empty)
	  ruleIndex += 2
	  var newsubs = List(newSubrule1,newSubrule2)
	  var newPol = new Policy("newPol"+policyIndex)(AlwaysTrue,FirstApplicable,newsubs,List.empty)
	  policyIndex += 1
	  newPol.parent = Some(policy)
	  var newpsubs = List(newRule,newPol)
	  policy.subpolicies = newpsubs
	  return newPol
	}
	
	def getAttributes(aPol: AbstractPolicy) : Set[Attribute] = aPol match{
	  case x:Policy => return getAttributes(x)
	  case x:Rule => return getAttributes(x)
	  case _ => return null
	}
	
	def getAttributes(policy : Policy) : Set[Attribute] = {
	  var result = Set[Attribute]()
	  result ++= getAttributes(policy.target)
	  for(s <- policy.subpolicies){
	    result ++= getAttributes(s)
	  }
	  return result
	}
	
	def getAttributes(rule : Rule):Set[Attribute] = return getAttributes(rule.condition)
	
	def getAttributes(expression:Expression) : Set[Attribute] = expression match{
	  case Or(x,y) => return getAttributes(x) ++ getAttributes(y)
	  case And(x,y) => return  getAttributes(x) ++ getAttributes(y)
	  case Not(x) => return getAttributes(x)
	  case BoolExpression(x) => return Set(x)
	  case GreaterThanValue(x,y) => return getAttributes(x,y)
	  case EqualsValue(x,y) => return getAttributes(x,y)
	  case ValueIn(x,y) => return getAttributes(x,y)
      case _ => return null
	}
	
	def getAttributes(x: Value, y: Value) : Set[Attribute] = {
	  var result = Set[Attribute]()
	  if(x.isInstanceOf[Attribute]) result += x.asInstanceOf[Attribute]
	  if(y.isInstanceOf[Attribute]) result += y.asInstanceOf[Attribute]
	  return result
	}	
	
	def findCommon(r1 : Rule, r2: Rule, atts: Set[Attribute], op: String) : Expression = {
	  var common:Set[Expression] = findCommons(r1,r2,op)
	  var result:Expression = common.head
	  var max = nbKnownAttributes(result, atts)
	  for(c <- common) {
	    var n = nbKnownAttributes(c,atts)
	    if(n > max) {
	      max = n
	      result = c
	    }
	  }
	  if(max >= 0)
		  return result
      else
    	  return null
	}
	
	def findCommons(r1 : Rule, r2: Rule,op: String) : Set[Expression]= {
	  var c1 = r1.condition
	  var c2 = r2.condition
	  var s1 = split(c1,op)
	  var s2 = split(c2,op)
	  var result = Set[Expression]()
	  for (e1 <- s1){
	    for(e2 <- s2) {
	      if(isEquivalent(e1,e2))
	        result += e1
	    }
	  }
	  return result
	}
	
	def isEquivalent(e1 : Expression, e2 : Expression):Boolean = {
	  var s1 = convertToSentence(e1)
	  var s2 = convertToSentence(e2)
	  var test = new TTEntails
	  var kb = new KnowledgeBase
	  kb.tell(s1)
	  return test.ttEntails(kb, s2) & s1.getNumberSimplerSentences() == s2.getNumberSimplerSentences()
	}
	
	def removeCommon(condition : Expression, common: Expression) : Expression = condition match{
	  case Or(x,y) => return Or(removeCommon(x,common),removeCommon(y,common))
	  case x if x == common => return AlwaysFalse
	  case x => return x
	}
	
	def split(condition: Expression, op: String) : Set[Expression] = op match {
	  case "And" => return splitAnd(condition)
	  case "Or" => return splitOr(condition)
	  case _ => return null
	}
	
	def splitOr(condition : Expression) : Set[Expression] = {
	  condition match{
	    case Or(x,y) => return splitOr(x) ++ splitOr(y)
	    case x => return Set(x)
	  }
	}
	
	def splitAnd(condition : Expression) : Set[Expression] = {
	  condition match{
	    case And(x,y) => return splitAnd(x) ++ splitAnd(y)
	    case x => return Set(x)
	  }
	}
	
	def nbKnownAttributes(condition : Expression, atts: Set[Attribute]) : Int = {
	  condition match{
	    case Or(x,y) => return nbKnownAttributes(x, atts) + nbKnownAttributes(y, atts)
	    case And(x,y) => return nbKnownAttributes(x, atts) + nbKnownAttributes(y, atts)
	    case Not(x) => return nbKnownAttributes(x, atts)
	    case BoolExpression(x) if atts.contains(x) => return 1
	    case BoolExpression(x) => return 0
	    case GreaterThanValue(x,y) => return nbAttributes(x,y,atts)
	    case EqualsValue(x,y) => return nbAttributes(x,y,atts)
	    case ValueIn(x,y) => return nbAttributes(x,y,atts)
	    case AlwaysTrue => return -1
	    case AlwaysFalse => return -1
	    case _ => return 0
	    }
	}
	
	def nbAttributes(x: Value, y: Value, atts: Set[Attribute]) : Int = {
	  if(x.isInstanceOf[Attribute] && y.isInstanceOf[Attribute]) {
	    var att1 = x.asInstanceOf[Attribute]
	    var att2 = y.asInstanceOf[Attribute]
	    if(atts.contains(att1) && atts.contains(att2)) return 2
	    else if(atts.contains(att1) || atts.contains(att2)) return 1
	    else return 0
	  }else if(x.isInstanceOf[Attribute]){
	    if(atts.contains(x.asInstanceOf[Attribute])) return 1
	    return 0
	  }else if(y.isInstanceOf[Attribute]){
	    if(atts.contains(y.asInstanceOf[Attribute])) return 1
	    return 0
	  }else return 0
	}
	
	def expandAnd(policies: List[Policy], atts: Set[Attribute]):Unit = {
	  for(p <- policies)
	    expandAnd(p,atts)
	}
	
	def expandAnd(policy: Policy, atts: Set[Attribute]):Unit= {
	  var r1 = policy.subpolicies(0).asInstanceOf[Rule]
	  var r2 = policy.subpolicies(1).asInstanceOf[Rule]
	  var common = findCommon(r1,r2,atts,"And")
	  if(common != null){
	    var e1 = r1.effect
	  var e2 = r2.effect
	  var cond1 = r1.condition
	  var cond2 = r2.condition
	  var newSubrule1 = new Rule(r1.id)(e1,removeCommonAnd(cond1,common),r1.obligationActions)
	  var newSubrule2 = new Rule(r2.id)(e2,removeCommonAnd(cond2,common),r2.obligationActions)
	  var newsubs = List(newSubrule1,newSubrule2)
	  var newPol = new Policy(policy.id)(policy.target,policy.pca,newsubs,policy.obligations)
	  newPol.parent = Some(policy)
	  newPol.parent match {
	    	case Some(x) => {x.subpolicies = replace(x.subpolicies,policy,newPol);expandAnd(x,atts)}
	    	case None => 
	    }
	    
	  }
	}
	
	def removeCommonAnd(condition: Expression, common: Expression):Expression = condition match{
	  case And(x,y) => return And(removeCommonAnd(x,common),removeCommonAnd(y,common))
	  case x if x == common => return AlwaysFalse
	  case x => return x
	}
	
	var ruleIndex = 0
	var policyIndex = 0

}