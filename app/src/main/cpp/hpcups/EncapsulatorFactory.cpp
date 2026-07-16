/*****************************************************************************\
  EncapsulatorFactory.cpp : Implementation of EncapsulatorFactory class

  Copyright (c) 1996 - 2015, HP Co.
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions
  are met:
  1. Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
  3. Neither the name of HP nor the names of its
     contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR IMPLIED
  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
  MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN
  NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
  TO, PATENT INFRINGEMENT; PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
  OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
\*****************************************************************************/

#include "CommonDefinitions.h"
#include "EncapsulatorFactory.h"
#include "Encapsulator.h"
#include "Pcl3.h"
#include "Pcl3Gui.h"
#include "Pcl3Gui2.h"
#include "LJMono.h"
#include "LJColor.h"
#include "LJFastRaster.h"
#include "LJZjStream.h"
#include "LJZxStream.h"
#include "Lidil.h"
#include <string.h>

Encapsulator *EncapsulatorFactory::GetEncapsulator (char *encap_tech)
{
    if (encap_tech == NULL) {
        return NULL;
    }

    // hbpl1 intentionally unsupported: needs PCLmGenerator, which needs
    // jpeglib.h — not part of this Android cross-build, and this printer
    // family (DeskJet, pcl3gui) never selects hbpl1 anyway.

    if (!strcmp (encap_tech, "pcl3"))
    {
        return new Pcl3();
    }
    if (!strcmp (encap_tech, "pcl3gui"))
    {
        return new Pcl3Gui();
    }
    if (!strcmp (encap_tech, "pcl3gui2"))
    {
        return new Pcl3Gui2();
    }
    if (!strcmp (encap_tech, "ljmono"))
    {
        return new LJMono();
    }
    if (!strcmp (encap_tech, "ljcolor"))
    {
        return new LJColor();
    }
    if (!strcmp (encap_tech, "ljfastraster"))
    {
        return new LJFastRaster();
    }
    if (!strcmp (encap_tech, "ljzjstream"))
    {
        return new LJZjStream();
    }
    if (!strcmp (encap_tech, "ljzxstream"))
    {
        return new LJZxStream();
    }
    // quickconnect/ljjetready (JPEG-based LaserJet modes) intentionally
    // unsupported: this build targets PCL3-GUI (DeskJet) only, and those
    // modes need jpeglib.h which isn't part of this Android cross-build.
    if (!strcmp (encap_tech, "lidil"))
    {
        return new Lidil();
    }
    return NULL;
}

