#!/usr/bin/env python3
"""Create an inspectable demo flow in the local, HTTP-only NiFi preview."""
import json,time,urllib.request,urllib.error
from pathlib import Path
BASE='http://localhost:18080/nifi-api'
def api(method,path,data=None):
    request=urllib.request.Request(BASE+path,method=method,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=20) as r:
            return json.load(r) if r.status!=204 else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'{method} {path}: {e.code} {e.read().decode()[:1000]}') from None
root=api('GET','/flow/process-groups/root')['processGroupFlow']
root_id=root['id']
for g in root['flow']['processGroups']:
    if g['component']['name']=='OPC UA Subscription Demo':
        print('Demo already exists:',g['id']);raise SystemExit(0)
types=api('GET','/flow/processor-types')['processorTypes']
def processor_type(suffix):
    return next(t for t in types if t['type'].endswith('.'+suffix))
group=api('POST',f'/process-groups/{root_id}/process-groups',{'revision':{'version':0},'component':{'name':'OPC UA Subscription Demo','position':{'x':250,'y':150}}})
gid=group['id']
def add_processor(kind,name,x,y,config):
    t=processor_type(kind)
    return api('POST',f'/process-groups/{gid}/processors',{'revision':{'version':0},'component':{'type':t['type'],'bundle':t['bundle'],'name':name,'position':{'x':x,'y':y},'config':config}})
source=add_processor('SubscribeOpcUa','Subscribe OPC UA — DemoSwitch + server time',150,180,{
    'schedulingPeriod':'100 ms','schedulingStrategy':'TIMER_DRIVEN','concurrentlySchedulableTaskCount':1,
    'properties':{
        'Endpoint URL':'opc.tcp://opcua:4840/demo',
        'Client Certificate File':'/security/client.der','Client Private Key File':'/security/client-key.der',
        'PKI Directory':'/security/nifi-pki','Username':'demo','Password':'demo-password',
        'Default Sampling Interval (ms)':'500',
        'Tags':'# One tag per line: optional alias | full NodeId\nDemoSwitch | ns=2;s=Demo.Boolean\nServer time | ns=0;i=2258'},
    'comments':'Local demo with generated certificates. Paste tags into Tags in Properties. Application URI is read from the certificate. All demo values and identifiers are synthetic.'})
sink=add_processor('LogAttribute','Inspect JSON in the incoming queue (stopped)',150,450,{'autoTerminatedRelationships':['success'],'comments':'Kept stopped so you can right-click the incoming connection, choose List queue, and view FlowFile content. Start this processor to drain the queue.'})
connection=api('POST',f'/process-groups/{gid}/connections',{'revision':{'version':0},'component':{
    'name':'JSON data changes — right-click → List queue',
    'source':{'id':source['id'],'groupId':gid,'type':'PROCESSOR'},
    'destination':{'id':sink['id'],'groupId':gid,'type':'PROCESSOR'},
    'selectedRelationships':['success'],'backPressureObjectThreshold':1000,'backPressureDataSizeThreshold':'10 MB','flowFileExpiration':'10 mins'}})
api('POST',f'/process-groups/{gid}/labels',{'revision':{'version':0},'component':{
    'label':'1. Configure Subscribe OPC UA → Properties.\n2. Paste your NodeId list into Tags, then enable and start.\n3. List queue → View content. Stop the source when finished.\nDemo uses SignAndEncrypt / Basic256Sha256 + certificate + username/password.',
    'position':{'x':120,'y':20},'width':640,'height':130,'style':{'font-size':'14px'}}})
# Wait for NiFi's asynchronous validation to complete.
for _ in range(30):
    state=api('GET','/processors/'+source['id'])
    if state['component'].get('validationStatus')=='VALID':break
    if state['component'].get('validationErrors'):print('Validation:',state['component']['validationErrors'])
    time.sleep(1)
else:raise RuntimeError('Demo source did not become valid')
api('PUT','/processors/'+source['id']+'/run-status',{'revision':state['revision'],'state':'DISABLED','disconnectedNodeAcknowledged':False})
result={'url':f'http://localhost:18080/nifi/?processGroupId={gid}','groupId':gid,'processorId':source['id'],'connectionId':connection['id']}
Path('demo/runtime/preview.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
