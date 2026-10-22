' Start BaleenCode without a console window and open it in its own app window
Option Explicit
Dim sh, fso, dir, i, edge, node
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir

Function ServerUp()
  On Error Resume Next
  Dim h
  Set h = CreateObject("MSXML2.ServerXMLHTTP")
  h.setTimeouts 500, 500, 500, 500
  h.open "GET", "http://127.0.0.1:4141/api/ping", False
  h.send
  ServerUp = (Err.Number = 0 And h.status = 204)
End Function

' Use a bundled Node.js (runtime\node.exe) if present, otherwise the one on PATH
node = dir & "\runtime\node.exe"
If Not fso.FileExists(node) Then node = "node"

If Not ServerUp() Then
  sh.Environment("PROCESS")("NO_OPEN") = "1"
  sh.Run """" & node & """ server.mjs", 0, False
  For i = 1 To 40
    WScript.Sleep 250
    If ServerUp() Then Exit For
  Next
End If

edge = sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%") & "\Microsoft\Edge\Application\msedge.exe"
If fso.FileExists(edge) Then
  sh.Run """" & edge & """ --app=http://localhost:4141 --window-size=960,860", 1, False
Else
  sh.Run "http://localhost:4141", 1, False
End If
